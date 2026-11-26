//! One actor owns every connection and the !Send Ghostty replica.
use anyhow::{Context, Result, ensure};
use rex_core::{
    control::{Control, Trace, UNDO_CLOSE_API_REVISION},
    data::{Data, Resume},
    ghostty::{Frame, Replica, Terminal},
    transport::Endpoint,
};
use serde_json::{Value, json};
use std::{
    collections::{BTreeMap,BTreeSet,VecDeque},
    future::{Future, pending},
    pin::Pin,
    sync::{Arc, Mutex, atomic::{AtomicU64,Ordering}},
    time::Duration,
};
use tokio::sync::mpsc;
use crate::input_queue::{Commands,Target,is_input};

pub struct Shared {
    pub revision: u64,
    pub json: String,
    pub activity: Arc<Value>,
    pub pending_previews: BTreeMap<String, Arc<Frame>>,
    pub frame: Option<Arc<Frame>>,
    pub key: String,
    pub wire: crate::updates::WireBase,
}
pub struct Session {
    latest_navigation: Arc<AtomicU64>,
    tx: mpsc::Sender<Value>,
    pub shared: Arc<Mutex<Shared>>,
    pub updates: Arc<crate::updates::Updates>,
}
impl Session {
    pub fn spawn() -> Result<Self> {
        let (tx, rx) = mpsc::channel(128);
        let shared = Arc::new(Mutex::new(Shared {
            revision: 0,
            json: String::new(),
            pending_previews: BTreeMap::new(),
            frame: None,
            key: String::new(),
            wire: crate::updates::WireBase::default(),activity:Arc::default(),
        }));
        let latest_navigation=Arc::new(AtomicU64::new(0));
        let worker_navigation=latest_navigation.clone();
        let worker = shared.clone();
        let updates=Arc::new(crate::updates::Updates::default());
        let notifier=updates.clone();
        std::thread::Builder::new()
            .name("rex-terminal".into())
            .spawn(move || {
                let runtime = tokio::runtime::Builder::new_current_thread()
                    .enable_all()
                    .build();
                if let Ok(runtime) = runtime {
                    let mut actor=Actor::new(worker,notifier);
                    actor.latest_navigation=worker_navigation;
                    runtime.block_on(actor.run(rx));
                }
            })?;
        Ok(Self { latest_navigation, tx, shared, updates })
    }
    pub fn command(&self, value: Value) -> Result<()> {
        // Publish intent at admission, even while the actor awaits an older RPC.
        // A refused command must not supersede work that was actually accepted.
        let permit=self.tx.try_reserve().context("terminal command queue is full or closed")?;
        if matches!(value["type"].as_str(),Some("block"|"session")) {
            if let Some(id)=value["navigationId"].as_u64() {self.latest_navigation.fetch_max(id,Ordering::AcqRel);}
        }
        permit.send(value);
        Ok(())
    }
    pub fn poll(&self, after: u64) -> Result<String> {
        let mut state = self
            .shared
            .lock()
            .map_err(|_| anyhow::anyhow!("terminal state unavailable"))?;
        if state.revision <= after {return Ok(String::new());}
        let mut value: Value=serde_json::from_str(&state.json)?;
        value["revision"]=json!(state.revision);
        value["activity"]=state.activity.as_ref().clone();
        value["screen"]=state.frame.as_ref().map_or(Value::Null,|frame|frame_value(frame));
        value["previews"]=Value::Object(std::mem::take(&mut state.pending_previews).into_iter().map(|(id,frame)|(id,frame_value(&frame))).collect());
        Ok(value.to_string())
    }
}
impl Drop for Session {
    fn drop(&mut self) {
        self.updates.close();
        let _ = self.tx.try_send(json!({"type":"shutdown"}));
    }
}

type InputReply = Pin<Box<dyn Future<Output = Result<Value>>>>;
struct InputAck {target:Target,paste:bool,reply:InputReply}
// Kotlin's marker for Shift+Enter (TerminalInput.SHIFT_ENTER).
const SHIFT_ENTER:&str="\x1b[13;2u";
fn input_event(paste:bool,text:&str)->Value {
    if paste {json!({"type":"paste","payload":{"text":text}})}
    // A real key event, so Rex encodes it for the program's keyboard mode:
    // ESC[13;2u with the kitty protocol, ESC[27;2;13~ otherwise.
    else if text==SHIFT_ENTER {json!({"type":"key","payload":{"key":"enter","mods":"shift","consumed_mods":"","text":"","unshifted_codepoint":13,"composing":false,"action":"press"}})}
    else {json!({"type":"key","payload":{"key":"unidentified","mods":"","consumed_mods":"","text":text,"unshifted_codepoint":0,"composing":false,"action":"press"}})}
}

fn wheel_text(terminal: &mut Terminal, value: &Value, lines: i32) -> Result<Option<String>> {
    let flags = terminal.input_flags()?;
    let bytes = if flags & 1 != 0 {
        terminal.wheel(lines > 0,
            value["col"].as_i64().unwrap_or(0) as i32,
            value["row"].as_i64().unwrap_or(0) as i32,
            value["cw"].as_u64().unwrap_or(1).clamp(1, 1024) as u32,
            value["ch"].as_u64().unwrap_or(1).clamp(1, 1024) as u32)?
    } else if flags & 6 == 6 {
        match (flags & 8 != 0, lines < 0) {
            (true, true) => b"\x1bOA".to_vec(),
            (true, false) => b"\x1bOB".to_vec(),
            (false, true) => b"\x1b[A".to_vec(),
            (false, false) => b"\x1b[B".to_vec(),
        }
    } else {
        return Ok(None);
    };
    String::from_utf8(bytes.repeat(lines.unsigned_abs() as usize))
        .map(Some)
        .context("Legacy mouse coordinates cannot be sent as UTF-8 terminal input")
}
#[cfg(test)]
mod shift_enter_tests {
    use super::*;
    #[test]
    fn shift_enter_becomes_a_key_event() {
        let event=input_event(false,SHIFT_ENTER);
        assert_eq!(event["payload"]["key"],"enter");
        assert_eq!(event["payload"]["mods"],"shift");
        assert_eq!(input_event(false,"\r")["payload"]["key"],"unidentified");
        assert_eq!(input_event(true,SHIFT_ENTER)["type"],"paste");
    }
}
type PreviewTask = Pin<Box<dyn Future<Output = Result<Vec<(String, Arc<Frame>)>>>>>;
fn pane_layout(window: &Value) -> impl Iterator<Item = &Value> {
    window["layout"].as_array().into_iter().flatten()
}

fn pane_window<'a>(windows: &'a Value, block: &str) -> Option<&'a Value> {
    windows.as_array()?.iter().find(|window| {
        window["blocks"].as_array().into_iter().flatten().any(|pane| pane["id"] == block)
    })
}

fn minimized_pane<'a>(windows: &'a Value, block: &str) -> Option<(&'a Value, &'a Value)> {
    let window = pane_window(windows, block)?;
    let pane = pane_layout(window).find(|pane| pane["id"] == block && pane["minimized"] == true)?;
    Some((window, pane))
}

fn visible_terminal(window: &Value) -> Option<&str> {
    let focused = window["focused"].as_str();
    window["blocks"].as_array()?.iter()
        .filter_map(|pane| pane["id"].as_str())
        .filter(|id| !pane_layout(window).any(|pane| pane["id"] == *id && pane["minimized"] == true))
        .min_by_key(|id| (Some(*id) != focused, pane_layout(window).any(|pane| pane["id"] == *id && pane["floating"] == true)))
}

fn visible_target(windows: &Value, block: String) -> String {
    let Some((window, _)) = minimized_pane(windows, &block) else { return block };
    visible_terminal(window)
        .or_else(|| windows.as_array()?.iter().find_map(visible_terminal))
        .unwrap_or_default().to_owned()
}

fn terminal_edit_call(windows:&Value,session:&str,value:&Value)->Result<(&'static str,Value)> {
    ensure!(value["session"].as_str()==Some(session),"The selected session changed");
    let kind=value["type"].as_str().context("terminal action required")?;
    let window=windows.as_array().into_iter().flatten().find(|w|w["id"]==value["window"]).context("That tab is no longer available")?;
    let id=value["id"].as_str().filter(|v|!v.is_empty()).context("terminal ID required")?;
    if kind=="rename_tab" {
        ensure!(window["id"]==id,"The tab changed");
    } else {ensure!(window["blocks"].as_array().into_iter().flatten().any(|b|b["id"]==id),"That pane is no longer available");}
    if kind=="rename_tab" || kind=="rename_pane" {
        let label=value["label"].as_str().context("name required")?.trim();
        ensure!(label.chars().count()<=128 && !label.chars().any(char::is_control),"Use a single-line name of at most 128 characters");
        return Ok(if kind=="rename_tab" {("session.set_window_label",json!({"session_id":session,"window_id":id,"label":label}))}
            else {("session.set_block_label",json!({"session_id":session,"block_id":id,"label":label}))});
    }
    let layout=window["layout"].as_array().context("Pane layout is unavailable")?;
    let placement=layout.iter().find(|p|p["id"]==id).context("Pane layout changed")?;
    if matches!(kind, "resize_floating" | "minimize_floating") {
        let layer=placement["layerId"].as_str().filter(|id|!id.is_empty()).context("Floating layer is unavailable")?;
        ensure!(placement["floating"]==true && value["layerId"]==layer,"The floating layer changed");
        if kind == "minimize_floating" {
            return Ok(("session.set_layer_state",json!({"session_id":session,"layer_id":layer,"state":"minimized"})));
        }
        let bounds=value["bounds"].as_array().filter(|rect|rect.len()==4).context("Invalid floating bounds")?;
        let fields: Option<Vec<_>>=bounds.iter().map(Value::as_f64).collect();
        let fields=fields.context("Invalid floating bounds")?;
        let (x,y,w,h)=(fields[0],fields[1],fields[2],fields[3]);
        ensure!(x>=0.0 && y>=0.0 && w>0.0 && h>0.0 && x<1.0 && y<1.0 &&
            x+w<=1.000001 && y+h<=1.000001,"Floating panes must fit inside the window");
        return Ok(("session.resize_layer",json!({"session_id":session,"layer_id":layer,"bounds":[x,y,w.min(1.0-x),h.min(1.0-y)]})));
    }
    if kind=="place_pane" {
        let anchor=value["anchor"].as_str().context("Choose a target pane")?;
        validate_tiled_drag(window,value,id,anchor)?;
        let (direction,side)=match value["position"].as_str() {
            Some("left")=>("horizontal","before"),
            Some("right")=>("horizontal","after"),
            Some("above")=>("vertical","before"),
            Some("below")=>("vertical","after"),
            _=>anyhow::bail!("Choose a side of the target pane"),
        };
        return Ok(("session.move_block",json!({"session_id":session,"block_id":id,"anchor_block_id":anchor,
            "direction":direction,"side":side,"ratio":0.5})));
    }
    let tiled:Vec<_>=layout.iter().filter(|p|p["floating"]==false).collect();
    if kind=="float_pane" {
        ensure!(placement["floating"]==false,"This pane is already floating");
        ensure!(tiled.len()>1,"Keep one docked pane in this tab");
        Ok(("session.float_block",json!({"session_id":session,"block_id":id,"bounds":[0.15,0.15,0.7,0.7]})))
    } else {
        ensure!(kind=="dock_pane" && placement["floating"]==true,"This pane is not floating");
        let anchor=value["anchor"].as_str().context("Choose a docked pane")?;
        ensure!(anchor!=id && tiled.iter().any(|p|p["id"]==anchor),"Choose an existing docked pane in this tab");
        let direction=match value["direction"].as_str() {Some("right")=>"horizontal",Some("below")=>"vertical",_=>anyhow::bail!("Choose right or below")};
        Ok(("session.move_block",json!({"session_id":session,"block_id":id,"anchor_block_id":anchor,"direction":direction,"side":"after","ratio":0.5})))
    }
}

fn new_split_arguments(session: &str,anchor: &str,command: &Value)->Result<Value> {
    let direction=match command.get("direction") {
        None=>"horizontal", // Preserve older callers' Split right behavior.
        Some(Value::String(value)) if matches!(value.as_str(),"horizontal"|"vertical")=>value.as_str(),
        _=>anyhow::bail!("Choose Split right or Split below"),
    };
    Ok(json!({"session_id":session,"anchor_id":anchor,"direction":direction,"side":"after",
        "layout":{"block":{"flavor":"com.superlogical.terminal.shell"}},"focus":false}))
}
fn split_anchor<'a>(windows: &Value,command: &'a Value)->Result<&'a str> {
    let anchor=command["anchor"].as_str().filter(|id|!id.is_empty()).context("Choose a pane first")?;
    ensure!(windows.as_array().into_iter().flatten().any(|w|w["blocks"].as_array().into_iter().flatten().any(|b|b["id"]==anchor)),
        "This pane is no longer available; choose another pane");
    Ok(anchor)
}
fn validate_tiled_drag(window: &Value,command: &Value,source: &str,target: &str)->Result<()> {
    let tiled: Vec<_> = window["layout"].as_array().into_iter().flatten()
        .filter(|pane| pane["floating"] == false)
        .filter_map(|pane| pane["id"].as_str())
        .collect();
    ensure!(window["zoomed"].as_str().is_none_or(str::is_empty)
        && command["tiled"] == json!(tiled), "The pane layout changed; try again");
    ensure!(source != target && tiled.contains(&source) && tiled.contains(&target),
        "Choose two different tiled panes");
    Ok(())
}
fn pane_reorder_swaps(windows: &Value,command: &Value)->Result<Vec<(String,String)>> {
    let window=windows.as_array().into_iter().flatten().find(|w|w["id"]==command["window"] && w["id"].is_string()).context("That tab is no longer available")?;
    if let Some(other) = command["swapWith"].as_str() {
        let block = command["id"].as_str().context("pane ID required")?;
        validate_tiled_drag(window,command,block,other)?;
        return Ok(vec![(block.to_owned(), other.to_owned())]);
    }
    let mut ids:Vec<String>=window["blocks"].as_array().into_iter().flatten().filter_map(|b|b["id"].as_str().map(str::to_owned)).collect();
    ensure!(!ids.is_empty() && ids.len()<=256 && ids.iter().collect::<BTreeSet<_>>().len()==ids.len(),"Invalid pane order");
    ensure!(command["blocks"]==json!(ids),"The pane layout changed; try again");
    let id=command["id"].as_str().context("pane ID required")?;
    let before=command["before"].as_str().filter(|s|!s.is_empty());
    ensure!(ids.iter().any(|p|p==id) && before.is_none_or(|b|ids.iter().any(|p|p==b)) && before!=Some(id),"That pane is no longer available");
    let mut wanted:Vec<_>=ids.iter().filter(|p|p.as_str()!=id).cloned().collect();
    let at=before.and_then(|b|wanted.iter().position(|p|p==b)).unwrap_or(wanted.len());
    wanted.insert(at,id.into());
    let mut swaps=Vec::new();
    for i in 0..ids.len() {
        if ids[i]!=wanted[i] {
            let j=ids.iter().position(|p|p==&wanted[i]).unwrap();
            swaps.push((ids[i].clone(),ids[j].clone()));ids.swap(i,j);
        }
    }
    Ok(swaps)
}
fn collect_pane_layout(node: &Value,ids: &BTreeSet<&str>,layer: usize,floating: bool,depth: usize,out: &mut Vec<Value>) {
    if !node.is_object() || depth>64 || out.len()>=256 {return;}
    if let Some(id)=node["block_id"].as_str()
        && ids.contains(id)
        && let Some(rect)=node["rect"].as_array()
        && rect.len()==4 && rect.iter().all(|v|v.as_f64().is_some_and(f64::is_finite))
        && rect[2].as_f64().unwrap()>0.0 && rect[3].as_f64().unwrap()>0.0 {
        out.push(json!({"id":id,"rect":rect,"layer":layer,"floating":floating}));
    }
    for child in ["before","after"] {collect_pane_layout(&node["split"][child],ids,layer,floating,depth+1,out);}
}
/// Any terminal block in a layout subtree; it stands for that side of a split in session.resize_pane.
fn first_block<'a>(node: &'a Value,ids: &BTreeSet<&str>,depth: usize) -> Option<&'a str> {
    if !node.is_object() || depth>64 {return None;}
    if let Some(id)=node["block_id"].as_str() && ids.contains(id) {return Some(id);}
    ["before","after"].iter().find_map(|child|first_block(&node["split"][*child],ids,depth+1))
}
/// A tiled layer's splits, for dragging pane borders: direction, ratio, the split's rect, and one
/// block from each side. Rex resolves the innermost split between two such blocks, which is this one.
fn collect_splits(node: &Value,ids: &BTreeSet<&str>,depth: usize,out: &mut Vec<Value>) {
    if !node.is_object() || depth>64 || out.len()>=64 {return;}
    let split=&node["split"];
    if !split.is_object() {return;}
    if let (Some(direction),Some(ratio),Some(rect),Some(before),Some(after))=(
        split["direction"].as_str().filter(|d|matches!(*d,"horizontal"|"vertical")),
        split["ratio"].as_f64().filter(|r|r.is_finite()),
        node["rect"].as_array().filter(|r|r.len()==4 && r.iter().all(|v|v.as_f64().is_some_and(f64::is_finite))),
        first_block(&split["before"],ids,depth+1),first_block(&split["after"],ids,depth+1)) {
        out.push(json!({"id":split["split_id"],"direction":direction,"ratio":ratio,"rect":rect,"before":before,"after":after}));
    }
    for child in ["before","after"] {collect_splits(&split[child],ids,depth+1,out);}
}
type ConnectTask = Pin<Box<dyn Future<Output = Result<Control>>>>;
type ProgramTask = Pin<Box<dyn Future<Output = Result<Value>>>>;
type SummaryTask = Pin<Box<dyn Future<Output = Result<Value>>>>;
// One inactive floating terminal stays live while its tiled pane is viewed.
// It never owns input, publishes pixels, or delivers clipboard side effects.
struct RetainedFloating {
    block: String,
    data: Data,
    replica: Replica,
}
const UNDO_CLOSE_WINDOW: Duration = Duration::from_secs(55);

struct UndoClose {
    serial: u64,
    tab: bool,
    expires: tokio::time::Instant,
}

struct Actor {
    latest_navigation: Arc<AtomicU64>,
    shared: Arc<Mutex<Shared>>,
    updates: Arc<crate::updates::Updates>,
    visible: bool,
    endpoint: Option<Endpoint>,
    server: String,
    control: Option<Control>,
    commands: Commands,
    input_acks: VecDeque<InputAck>,
    input_limit: usize,
    batch_text: bool,
    connect_task: Option<ConnectTask>,
    network_online: bool,
    connection_epoch: u64,
    other_owner: bool,
    data: Option<Data>,
    retained_floating: Option<RetainedFloating>,
    /// The multi-pane layout's other panes; empty, and inert, in the single-pane layout.
    mirror: crate::mirror::Mirror,
    mirror_waiting: Vec<String>,
    replica: Option<Replica>,
    control_token: Option<String>,
    data_resume: Option<Resume>,
    phase: String,
    identity: String,
    server_version: String,
    server_version_reply: Option<ProgramTask>,
    error: String,
    notice: String,
    supports_undo_close: bool,
    undo_close_serial: u64,
    undo_close: Option<UndoClose>,
    progress_reports: BTreeMap<String,Value>,
    clipboard_serial: u64,
    clipboard: Value,
    sessions: Value,
    windows: Value,
    screen: Option<Arc<Frame>>,
    selected_session: String,
    stale_session: String,
    selected_block: String,
    awaiting_pane_selection: bool,
    navigation_id: u64,
    tab_move_id: u64,
    pane_move_id: u64,
    controlled: bool,
    owned_blocks: BTreeSet<String>,
    theme: Option<Value>,
    creating: bool,
    paused: bool,
    retry: Option<tokio::time::Instant>,
    backoff: u64,
    dirty: bool,
    preview_task: Option<PreviewTask>,
    summary_task: Option<SummaryTask>,
    pane_catalog_task: Option<SummaryTask>,
    pane_catalog: Value,
    activity_open: bool,
    monitoring: bool,
    activity_task: Option<ProgramTask>,
    activity_refresh: tokio::time::Instant,
    activity: Value,
    activity_snapshot: Arc<Value>,
    activity_dirty: bool,
    activity_panes: Value,
    activity_titles: BTreeMap<String,Value>,
    activity_full: bool,
    activity_inflight_full: bool,
    activity_pending: BTreeSet<String>,
    activity_inflight: BTreeSet<String>,
    activity_seen: Value,
    attention_count: usize,
    activity_error: String,
    activity_partial: bool,
    program_task: Option<ProgramTask>,
    programs: Value,
    directories: Value,
    titles: Value,
    working: Value,
    title_owners: Value,
    title_events: BTreeMap<String, Value>,
    process_events: BTreeMap<String, Value>,
    program_refresh: tokio::time::Instant,
    preview_updates: BTreeMap<String, Arc<Frame>>,
    session_details: Value,
}
impl Actor {
    fn new(shared: Arc<Mutex<Shared>>,updates: Arc<crate::updates::Updates>) -> Self {
        Self {
            latest_navigation:Arc::new(AtomicU64::new(0)),
            shared,
            updates,
            visible: true,
            endpoint: None,
            server: String::new(),
            control: None,commands:Commands::default(),input_acks:VecDeque::new(),input_limit:8,batch_text:true,connect_task:None,network_online:true,connection_epoch:0,other_owner:false,
            data: None,
            retained_floating: None,
            mirror: Default::default(),
            mirror_waiting: Vec::new(),
            replica: None,
            control_token: None,
            data_resume: None,
            phase: "disconnected".into(),
            identity: String::new(),
            server_version: String::new(),
            server_version_reply: None,
            error: String::new(),
            notice: String::new(),
            supports_undo_close: false,
            undo_close_serial: 0,
            undo_close: None,
            progress_reports:BTreeMap::new(),
            clipboard_serial: 0,clipboard: Value::Null,
            sessions: json!([]),
            windows: json!([]),
            screen: None,
            selected_session: String::new(),
            stale_session: String::new(),
            selected_block: String::new(),
            awaiting_pane_selection: false,
            navigation_id: 0,
            tab_move_id: 0,
            pane_move_id: 0,
            controlled: false,
            owned_blocks: BTreeSet::new(),
            theme: None,
            creating: false,
            paused: false,
            retry: None,
            backoff: 1,
            dirty: false,
            preview_task: None,
            summary_task: None,
            pane_catalog_task: None,
            pane_catalog: Value::Null,
            activity_open:false,monitoring:false,activity_task:None,activity_refresh:tokio::time::Instant::now(),
            activity:json!([]),activity_snapshot:Arc::default(),activity_dirty:true,activity_panes:json!([]),activity_titles:BTreeMap::new(),activity_full:false,activity_inflight_full:false,activity_pending:BTreeSet::new(),activity_inflight:BTreeSet::new(),activity_seen:json!({}),attention_count:0,activity_error:String::new(),activity_partial:false,
            program_task: None,
            programs: json!({}),
            directories: json!({}),
            titles: json!({}),
            working: json!([]),
            title_owners: json!({}),
            title_events: BTreeMap::new(),
            process_events: BTreeMap::new(),
            program_refresh: tokio::time::Instant::now(),
            preview_updates: BTreeMap::new(),
            session_details: json!({}),
        }
    }
    fn publish(&mut self) {
        self.mirror_waiting = self.mirror.waiting().map(str::to_owned).collect();
        if self.activity_dirty {
            let mut items=crate::progress::activity(&self.activity,&self.activity_panes,&self.progress_reports);
            for item in items.as_array_mut().into_iter().flatten().filter(|v|v["progressOnly"]==true && v["session"]==self.selected_session) {
                let id=item["block"].as_str().unwrap_or("").to_owned();
                if let Some(app)=self.programs[&id].as_array().and_then(|v|v.first()).and_then(Value::as_str) {item["app"]=json!(app);}
                if let Some(title)=self.titles[&id].as_str().filter(|v|!v.is_empty()) {item["title"]=json!(title);}
            }
            self.activity_snapshot=Arc::new(items);self.activity_dirty=false;
        }
        if let Ok(mut shared) = self.shared.lock() {
            shared.activity=self.activity_snapshot.clone();
            shared.revision += 1;
            shared.frame=self.screen.clone();
            shared.key=format!("{}:{}",self.selected_session,self.selected_block);
            shared.json=json!({"phase":self.phase,"endpoint":self.server,
                "terminalProgress":crate::progress::selected(&self.progress_reports,&self.selected_session),"identity":self.identity,"error":self.error,"notice":self.notice,"clipboard":self.clipboard,"sessions":self.sessions,
                "mirrorWaiting":self.mirror_waiting,"windows":self.windows,"selectedSession":self.selected_session,"staleSession":self.stale_session,
                "awaitingPaneSelection":self.awaiting_pane_selection,
                "serverVersion":self.server_version,
                "supportsUndoClose":self.phase=="connected" && self.supports_undo_close,
                "undoClose":self.undo_close.as_ref().map(|close|json!({"serial":close.serial,"tab":close.tab})),
                "selectedBlock":self.selected_block,"navigationId":self.navigation_id,"tabMoveId":self.tab_move_id,"paneMoveId":self.pane_move_id,"connectionEpoch":self.connection_epoch,"otherOwner":self.other_owner,"controlled":self.controlled,"creating":self.creating,"sessionDetails":self.session_details,"paneCatalog":self.pane_catalog,"paneCatalogLoading":self.pane_catalog_task.is_some(),"programs":self.programs,"directories":self.directories,"titles":self.titles,"working":self.working,"activityError":self.activity_error,"activityPartial":self.activity_partial,"activityLoading":(self.activity_open || self.monitoring) && (self.activity_task.is_some() || self.activity_full),"attentionCount":self.attention_count}).to_string();
            shared
                .pending_previews
                .extend(std::mem::take(&mut self.preview_updates));
            self.updates.publish(shared.revision);
        }
    }
    /// Hands mirror frames to Android without rebuilding the state JSON, which they leave unchanged.
    fn publish_mirror_frames(&mut self) {
        if let Ok(mut shared)=self.shared.lock() {
            shared.revision+=1;
            shared.pending_previews.extend(std::mem::take(&mut self.preview_updates));
            self.updates.publish(shared.revision);
        }
    }
    fn publish_mirror_status(&mut self) {
        if !self.mirror.waiting().eq(self.mirror_waiting.iter().map(String::as_str)) {
            self.publish();
        }
    }
    fn publish_frame(&mut self) {
        if let Ok(mut shared)=self.shared.lock() {
            shared.revision+=1;shared.frame=self.screen.clone();
            self.updates.publish(shared.revision);
        }
    }
    async fn run(mut self, mut rx: mpsc::Receiver<Value>) {
        self.publish();
        let mut render_at=tokio::time::Instant::now();
        let mut maintenance_at=render_at;
        let mut animation_at:Option<tokio::time::Instant>=None;
        let mut heartbeat = tokio::time::interval(Duration::from_secs(120));
        heartbeat.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        heartbeat.tick().await;
        loop {
            // RPC replies can carry earlier events; consume those before reading the socket again.
            if let Some(event)=self.control.as_mut().and_then(|c|c.events.pop_front()) {
                if let Err(error)=self.event(event).await {self.recover(error);}
                continue;
            }
            if !self.input_acks.iter().any(|ack|ack.paste) && self.commands.ready(self.input_acks.len(),self.input_limit) {
                if let Some(value)=self.commands.pop(self.batch_text) {
                    if is_input(&value) {
                        if let Err(error)=self.send_input(value).await {self.command_error(error,None,true);}
                    } else {self.execute_command(value).await;}
                }
                continue;
            }
            let active = !self.paused;
            if self.visible && active && let Some(control) = self.control.as_mut() {
                self.mirror.start(control).await;
            }
            let mirror_due = self.mirror.due().filter(|_| self.visible);
            let mirror_retry = self.mirror.retry_due().filter(|_| self.visible && active && self.control.is_some());
            let undo_expires = self.undo_close.as_ref().map(|close| close.expires);
            tokio::select! {
                _ = async { if let Some(expires) = undo_expires { tokio::time::sleep_until(expires).await; } else { pending::<()>().await; } } => {
                    self.undo_close = None;
                    self.publish();
                },
                value=rx.recv()=>{
                    let Some(value)=value else{break;};
                    if value["type"]=="shutdown"{break;}
                    if value["type"]=="visibility" {
                        self.visible=!self.monitoring && value["visible"].as_bool().unwrap_or(true);
                        if self.visible {self.dirty=true;render_at=tokio::time::Instant::now();}
                        else {
                            self.commands.clear();
                            if let Err(error)=self.release_session_panes().await {self.recover(error);}self.publish();
                        }
                        continue;
                    }
                    // Local scrollback does not issue RPCs or mutate input ownership.
                    // Render a scroll on the next loop pass instead of the next 16 ms slot: the view is
                    // sliding locally and runs out of overscan rows while it waits for this frame.
                    if value["type"]=="scroll" {self.execute_command(value).await;if self.dirty {render_at=tokio::time::Instant::now();}continue;}
                    if matches!(value["type"].as_str(),Some("connect"|"retry"|"network"|"resume"|"activity")) {maintenance_at=tokio::time::Instant::now();}
                    if value["type"]=="network" {
                        self.execute_command(value).await;
                    } else if matches!(value["type"].as_str(),Some("monitor"|"connect"|"disconnect"|"pause"|"retry")) {
                        self.commands.clear();self.execute_command(value).await;
                    } else if let Err(error)=self.commands.push(value,&self.input_target()) {self.error=error.to_string();self.publish();}

                },
                result=async {match self.input_acks.front_mut() {Some(ack)=>(&mut ack.reply).await,None=>pending().await}}=>{
                    let ack=self.input_acks.pop_front().unwrap();
                    if let Err(error)=result {
                        if error.downcast_ref::<rex_core::control::RpcError>().is_some() {
                            if ack.target==self.input_target() && self.controlled {
                                self.commands.discard_input();self.controlled=false;self.error=error.to_string();
                                self.notice="Input rejected; queued input was not resent".into();
                                let _=self.commands.push(json!({"type":"refresh"}),&self.input_target());self.publish();
                            }
                        } else if ack.target.connection==self.connection_epoch {self.recover(error);self.notice="Request outcome is uncertain; input was not resent".into();self.publish();}
                    }
                },
                result=async {match &mut self.server_version_reply {
                    Some(reply)=>reply.await,None=>pending().await
                }}=>{
                    self.server_version_reply = None;
                    if let Ok(version) = result
                        && let Some(version) = version["rex_version"].as_str()
                        && !version.trim().is_empty() {
                        self.server_version = version.trim().chars().filter(|c| !c.is_control()).take(100).collect();
                        self.publish();
                    }
                },
                result=async {match &mut self.connect_task {Some(task)=>task.await,None=>pending().await}}=>{
                    self.connect_task=None;
                    match result {Ok(control)=>if let Err(error)=self.connected(control).await {self.recover(error);},Err(error)=>self.recover(error)}
                },
                message=async {match &mut self.control{Some(c)=>c.next_message().await,None=>pending().await}}=>{
                    match message {
                        Ok(message)=>if message["type"]=="event" && let Err(e)=self.event(message).await {self.recover(e);},
                        Err(e)=>self.recover(e),
                    }
                },
                chunk=async {match &mut self.data{Some(d)=>d.read().await,None=>pending().await}}=>{
                    match chunk {
                        Ok(chunk)=>{
                            let result=self.replica.as_mut().context("terminal replica missing").and_then(|r|r.feed(&chunk));
                            if let Err(e)=result {
                                self.error=format!("Terminal state could not be decoded: {e}");
                                self.data=None;self.replica=None;self.data_resume=None;self.phase="error".into();self.controlled=false;self.publish();
                            } else {
                                self.dirty=true;
                                self.update_apcs();
                            }
                        }
                        Err(e)=>self.recover(e),
                    }
                },
                event = self.mirror.next(), if self.visible && active && self.control.is_some() => {
                    if let Some(endpoint) = self.endpoint.as_ref() {
                        self.mirror.handle(event, endpoint);
                        self.publish_mirror_status();
                    }
                },
                _ = async { if let Some(at) = mirror_retry { tokio::time::sleep_until(at).await; } else { pending::<()>().await; }} => {},
                _ = async { if let Some(at) = mirror_due { tokio::time::sleep_until(at).await; } else { pending::<()>().await; }} => {
                    let frames = self.mirror.frames();
                    if !frames.is_empty() {
                        self.preview_updates.extend(frames);
                        if self.mirror.waiting().eq(self.mirror_waiting.iter().map(String::as_str)) {
                            self.publish_mirror_frames();
                        } else {
                            self.publish();
                        }
                    } else {
                        self.publish_mirror_status();
                    }
                },
                chunk=async {match &mut self.retained_floating {Some(p)=>p.data.read().await,None=>pending().await}}=>{
                    let keep=if let (Ok(bytes),Some(pane))=(chunk,self.retained_floating.as_mut()) {
                        let ok=pane.replica.feed(&bytes).is_ok();
                        // Inactive terminals must not replay clipboard writes or old
                        // status events when restored; control events track status.
                        pane.replica.events.clear();pane.replica.clipboard_write=None;
                        ok
                    } else {false};
                    if !keep {self.close_retained_floating().await;}
                },
                result=async {match &mut self.preview_task {Some(task)=>task.await,None=>pending().await}}=>{
                    self.preview_task=None;
                    if let Ok(frames)=result {
                        self.preview_updates.extend(frames.into_iter().filter(|(id,_)| minimized_pane(&self.windows,id).is_none()));
                        self.publish();
                    }
                },
                result=async {match &mut self.summary_task {Some(task)=>task.await,None=>pending().await}}=>{
                    self.summary_task=None;
                    if let Ok(details)=result {self.session_details=details;self.publish();}
                },
                result=async {match &mut self.pane_catalog_task {Some(task)=>task.await,None=>pending().await}}=>{
                    self.pane_catalog_task=None;
                    match result {
                        Ok(catalog)=>self.pane_catalog=catalog,
                        Err(_)=> {
                            if self.pane_catalog.is_null() {self.pane_catalog=json!({});}
                            self.pane_catalog["error"]=json!(true);
                        }
                    }
                    self.publish();
                },
                result=async {match &mut self.activity_task {Some(task)=>task.await,None=>pending().await}}=>{
                    self.activity_task=None;self.activity_refresh=tokio::time::Instant::now()+Duration::from_secs(1);
                    match result {
                        Ok(value)=>{
                            let next=if value["full"]==true {
                                self.activity_panes=value["panes"].clone();value["items"].clone()
                            } else {
                                let replaced:BTreeSet<_>=value["replace"].as_array().into_iter().flatten().filter_map(Value::as_str).collect();
                                let mut items:Vec<_>=self.activity.as_array().into_iter().flatten().filter(|item|
                                    !replaced.contains(crate::activity::pane_key(item["session"].as_str().unwrap_or(""),item["block"].as_str().unwrap_or("")).as_str())).cloned().collect();
                                items.extend(value["items"].as_array().into_iter().flatten().cloned());items.truncate(1024);json!(items)
                            };
                            if self.activity!=next {self.activity=next;self.activity_dirty=true;}
                            self.activity_partial=value["partial"]==true;self.activity_error.clear();
                        },
                        Err(e)=>{
                            self.activity_error=e.to_string();self.activity_refresh=tokio::time::Instant::now()+Duration::from_secs(5);
                            if self.activity_inflight_full {self.activity_full=true;} else {self.activity_pending.extend(self.activity_inflight.iter().cloned());}
                        }
                    }
                    self.activity_inflight.clear();
                    for cached in self.activity_titles.values() {
                        if crate::activity::apply_title(&mut self.activity,cached["session"].as_str().unwrap_or(""),cached["block"].as_str().unwrap_or(""),&cached["event"]) {self.activity_dirty=true;}
                    }
                    self.attention_count=crate::activity::attention_count(&self.activity,&self.activity_seen);
                    self.publish();
                },
                result=async {match &mut self.program_task {Some(task)=>task.await,None=>pending().await}}=>{
                    self.program_task=None;
                    if let Ok(details)=result {self.apply_program_details(details);self.publish();}
                },
                _=async {if active {let deadline=if self.dirty && self.visible {render_at}else{maintenance_at};
                    tokio::time::sleep_until(animation_at.filter(|_|self.visible).map_or(deadline,|image|image.min(deadline))).await;}else{pending::<()>().await;}}=>{
                    let now=tokio::time::Instant::now();
                    render_at=now+Duration::from_millis(16);
                    maintenance_at=now+Duration::from_secs(1);
                    if self.network_online && self.connect_task.is_none() && self.retry.is_some_and(|at|at<=now) && !self.paused {
                        self.retry=None;
                        if let Some(endpoint)=self.endpoint.clone() {
                            let token=self.control_token.clone();
                            let monitoring=self.monitoring;
                            self.connect_task=Some(Box::pin(async move {
                                if monitoring {Control::connect(endpoint,token.as_deref(),Trace::default()).await}
                                else {Control::connect_workspace(endpoint,token.as_deref()).await}
                            }));
                        }
                    }
                    if self.phase=="connected" && self.activity_task.is_none() && self.activity_refresh<=now && (self.activity_full || !self.activity_pending.is_empty()) {
                        self.activity_inflight_full=self.activity_full;
                        self.activity_inflight=std::mem::take(&mut self.activity_pending);
                        self.activity_task=if self.activity_full {
                            self.activity_full=false;Some(Box::pin(crate::activity::scan(self.endpoint.clone().unwrap())))
                        } else {
                            let panes=self.activity_panes.as_array().into_iter().flatten().filter(|p|
                                self.activity_inflight.contains(&crate::activity::pane_key(p["session"]["session_id"].as_str().unwrap_or(""),p["block"]["block_id"].as_str().unwrap_or("")))).cloned().collect();
                            Some(Box::pin(crate::activity::refresh(self.endpoint.clone().unwrap(),panes)))
                        };
                        if self.activity_open || self.monitoring {self.publish();}
                    }
                    if self.phase=="connected" && self.program_task.is_none() && !self.selected_session.is_empty()
                        && self.program_refresh<=tokio::time::Instant::now()
                    {
                        let ids=self.windows.as_array().into_iter().flatten().flat_map(|w|
                            w["blocks"].as_array().into_iter().flatten().filter_map(|b|b["id"].as_str().map(str::to_owned))).collect();
                        self.program_task=Some(program_task(self.endpoint.clone().unwrap(),self.selected_session.clone(),ids));
                        self.program_refresh=tokio::time::Instant::now()+Duration::from_secs(4);
                    }
                    if self.visible && (self.dirty || animation_at.is_some_and(|at|at<=now)) {
                        self.dirty=false;animation_at=None;
                        if let Some(replica)=self.replica.as_mut().filter(|r|r.ready()) {
                            match replica.terminal().and_then(|t|if t.render_ready()? {t.frame().map(Some)} else {Ok(None)}) {
                                Ok(Some(frame))=>{animation_at=frame.next_image_delay_ms.map(|ms|now+Duration::from_millis(ms));self.screen=Some(Arc::new(frame));self.publish_frame();}
                                Ok(None)=>{self.dirty=true;}
                                Err(e)=>{self.error=e.to_string();self.publish();}
                            }
                        }
                    }
                },
                _=async {if active {heartbeat.tick().await;}else{pending::<()>().await;}}=>{
                    let result=async {
                        if let Some(c)=&mut self.control{c.ping().await?;}
                        if let Some(d)=&mut self.data{d.ping().await?;}
                        Ok::<(),anyhow::Error>(())
                    }.await;
                    if let Err(e)=result{self.recover(e);}
                },
            }
        }
        self.disconnect().await;
    }
    async fn close_retained_floating(&mut self) {
        if let Some(mut pane)=self.retained_floating.take() {
            let _=tokio::time::timeout(Duration::from_secs(2),pane.data.close()).await;
        }
    }
    async fn disconnect(&mut self) {
        self.server_version.clear();
        self.server_version_reply = None;
        self.undo_close = None;
        let _ = tokio::time::timeout(Duration::from_secs(2), self.release_session_panes()).await;
        self.close_retained_floating().await;
        self.awaiting_pane_selection = false;
        self.mirror=Default::default();
        self.stale_session.clear();
        self.commands.clear();self.input_acks.clear();
        self.connect_task=None;self.other_owner=false;
        self.preview_task = None;
        self.summary_task = None;
        self.pane_catalog_task = None;
        self.pane_catalog = Value::Null;
        self.activity_task=None;
        self.program_task = None;
        self.preview_updates.clear();
        self.session_details = json!({});self.activity=json!([]);self.activity_dirty=true;self.activity_panes=json!([]);self.activity_titles.clear();self.attention_count=0;self.activity_pending.clear();self.activity_inflight.clear();self.activity_full=false;self.activity_error.clear();self.activity_partial=false;
        self.programs = json!({});
        self.directories = json!({});self.titles=json!({});self.working=json!([]);self.title_owners=json!({});self.title_events.clear();self.process_events.clear();
        self.retry = None;
        self.control_token = None;
        self.data_resume = None;
        if let Some(mut d) = self.data.take() {
            let _ = tokio::time::timeout(Duration::from_secs(2), d.close()).await;
        }
        if let Some(mut c) = self.control.take() {
            let _ = tokio::time::timeout(Duration::from_secs(2), c.close()).await;
        }
        self.controlled = false;
        self.owned_blocks.clear();
    }
    fn lose_connections(&mut self) {
        self.server_version.clear();
        self.server_version_reply = None;
        self.undo_close = None;
        self.owned_blocks.clear();
        self.retained_floating=None;
        self.mirror.clear();
        self.progress_reports.clear();self.activity_dirty=true;
        self.commands.clear();self.input_acks.clear();
        self.connect_task=None;self.other_owner=false;
        self.preview_task = None;
        self.summary_task = None;
        self.pane_catalog_task = None;
        self.activity_task=None;
        self.program_task = None;
        if let Some(c) = self.control.take() {
            self.control_token = c.token().map(str::to_owned);
            drop(c);
        }
        if let Some(data) = self.data.take() {
            self.data_resume = Some(data.disconnect());
        }
        self.controlled = false;
    }
    fn recover(&mut self, error: anyhow::Error) {
        let input_pending=!self.input_acks.is_empty();
        self.lose_connections();
        if input_pending {self.notice="Request outcome is uncertain; input was not resent".into();}
        self.error = error.to_string();
        if self.endpoint.is_some() && !self.paused {
            self.phase = "reconnecting".into();
            self.retry = self.network_online.then(||tokio::time::Instant::now() + Duration::from_secs(self.backoff));
            self.backoff = (self.backoff * 2).min(15);
        }
        self.publish();
    }
    fn clear_ended_session(&mut self, session: String) {
        if self.selected_session == session { self.undo_close = None; }
        self.owned_blocks.clear();
        self.stale_session = session;
        self.selected_session.clear();
        self.selected_block.clear();
        self.awaiting_pane_selection = false;
        self.windows = json!([]);
        self.mirror = Default::default();
        self.retained_floating = None;
        self.data = None;
        self.data_resume = None;
        self.replica = None;
        self.screen = None;
        self.dirty = false;
        self.controlled = false;
        self.other_owner = false;
        self.clipboard = Value::Null;
        self.commands.discard_input();
        self.preview_task = None;
        self.program_task = None;
        self.summary_task = None;
        self.preview_updates.clear();
        if let Ok(mut shared) = self.shared.lock() {
            shared.pending_previews.clear();
        }
        self.programs = json!({});
        self.directories = json!({});
        self.titles = json!({});
        self.working = json!([]);
        self.title_owners = json!({});
        self.title_events.clear();
        self.process_events.clear();
        if let Some(sessions) = self.sessions.as_array_mut() {
            sessions.retain(|item| item["session_id"] != self.stale_session);
        }
        self.progress_reports.retain(|_, report| report["session"] != self.stale_session);
        self.activity_full = true;
        self.activity_dirty = true;
        self.error.clear();
        self.notice = "Session ended. Start or choose another session.".into();
    }

    async fn refresh_session_catalog(&mut self) -> Result<()> {
        self.sessions = self.control.as_mut().context("not connected")?
            .call("session.list", json!({})).await?["sessions"].clone();
        Ok(())
    }

    async fn connected(&mut self,mut control: Control) -> Result<()> {
        let endpoint=self.endpoint.clone().context("server missing")?;
        let welcome = control.welcome();
        let resumed = welcome["client"]["resumed"] == true;
        self.supports_undo_close = welcome["api_revision"].as_u64().is_some_and(|revision| revision >= UNDO_CLOSE_API_REVISION);
        self.control_token = control.token().map(str::to_owned);
        self.sessions = control
            .call("session.attach_all", json!({"event_scope":"all"}))
            .await?["sessions"]
            .clone();
        let who = control.call("server.whoami", json!({})).await?;
        self.identity = who["principal"]["display"]
            .as_str()
            .unwrap_or("Connected")
            .to_owned();
        // Version is supplementary metadata. Don't wait for it before opening a pane.
        if !self.monitoring && let Ok(reply) = control.start_call("server.version", json!({})).await {
            self.server_version_reply = Some(Box::pin(reply.receive()));
        }
        self.control = Some(control);
        self.phase = "connected".into();self.connection_epoch=self.connection_epoch.wrapping_add(1);
        self.awaiting_pane_selection = false;
        self.activity_full=true;self.activity_refresh=tokio::time::Instant::now();
        self.error.clear();
        self.backoff = 1;
        if !self.selected_session.is_empty() {
            let session = self.selected_session.clone();
            let attached = match self.control.as_mut().unwrap().attach(&session).await {
                Ok(attached) => attached,
                Err(error) if missing_terminal(&error) => {
                    self.clear_ended_session(session);
                    self.publish();
                    return Ok(());
                }
                Err(error) => return Err(error),
            };
            self.set_view(&attached["view"]);
            if !self.visible && !self.owned_blocks.is_empty() {
                self.release_session_panes().await?;
            }
            if minimized_pane(&self.windows, &self.selected_block).is_some() {
                self.release_selected().await?;
                self.selected_block = visible_target(&self.windows, std::mem::take(&mut self.selected_block));
                self.data_resume = None;
                self.replica = None;
                self.screen = None;
            }
            if !self.selected_block.is_empty() {
                let block = self.selected_block.clone();
                self.apply_theme().await?;
                let mut recovered = None;
                if resumed && let Some(state) = self.data_resume.take() {
                    recovered = state.connect(&endpoint).await.ok();
                }
                self.data = if let Some(data) = recovered {
                    Some(data)
                } else {
                    let data = Data::open(
                        &endpoint,
                        self.control.as_mut().unwrap(),
                        &session,
                        &block,
                        Trace::default(),
                    )
                    .await?;
                    self.replica = Some(Replica::new(data.nonce));
                    Some(data)
                };
                self.data_resume = None;
                self.notice = if resumed {
                    "Connection restored"
                } else {
                    "Connected; terminal state refreshed"
                }
                .into();
            }

        }
        self.publish();
        Ok(())
    }
    fn set_view(&mut self, view: &Value) {
        self.stale_session.clear();
        self.other_owner=false;
        let client = self
            .control
            .as_ref()
            .and_then(|c| c.client_id())
            .unwrap_or("");
        let mut windows = Vec::new();
        let mut selected_exists = false;
        self.owned_blocks.clear();
        let mut minimized = BTreeSet::new();
        for window in view["windows"].as_array().into_iter().flatten() {
            let mut blocks = Vec::new();
            let mut layout=Vec::new();
            let mut splits=Vec::new();
            for (index,layer) in window["layers"].as_array().into_iter().flatten().enumerate() {
                let ids=layer["blocks"].as_array().into_iter().flatten()
                    .filter(|b|b["creator_name"]=="com.superlogical.terminal")
                    .filter_map(|b|b["block_id"].as_str()).collect();
                let start=layout.len();
                collect_pane_layout(&layer["layout"],&ids,index,layer["kind"]!="tiled",0,&mut layout);
                if layer["kind"]=="floating" && layer["layer_id"].is_string() {
                    for pane in &mut layout[start..] {
                        pane["layerId"]=layer["layer_id"].clone();
                        pane["layerBounds"]=layer["bounds"].clone();
                        if layer["state"] == "minimized" {
                            pane["minimized"] = json!(true);
                            if let Some(id) = pane["id"].as_str() { minimized.insert(id.to_owned()); }
                        }
                    }
                }
                if layer["kind"]=="tiled" {collect_splits(&layer["layout"],&ids,0,&mut splits);}
                for block in layer["blocks"].as_array().into_iter().flatten() {
                    if block["creator_name"] != "com.superlogical.terminal" {
                        continue;
                    }
                    let id = block["block_id"].as_str().unwrap_or("");
                    if !id.is_empty() && !client.is_empty() && block["owner"].as_str() == Some(client) {
                        self.owned_blocks.insert(id.to_owned());
                    }
                    if id == self.selected_block {
                        selected_exists = true;
                        self.controlled = block["owner"].as_str() == Some(client);
                        self.other_owner=block["owner"].as_str().is_some_and(|owner|!owner.is_empty() && owner!=client);
                        if self.controlled {clear_ownership_error(&mut self.error);}
                    }
                    blocks.push(
                        json!({"id":id,"label":block["label"].as_str().unwrap_or("Terminal")}),
                    );
                }
            }
            // Rex draws a zoomed block over its whole window; layouts keep their normal sizes.
            windows.push(json!({"id":window["window_id"],"label":window["label"],"blocks":blocks,"layout":layout,"splits":splits,
                "zoomed":window["zoomed_block_id"].as_str().unwrap_or(""),"focused":window["focused_block_id"]}));
        }
        let next = json!(windows);
        let ids = |windows: &Value| -> BTreeSet<String> {
            windows
                .as_array()
                .into_iter()
                .flatten()
                .flat_map(|w| {
                    w["blocks"]
                        .as_array()
                        .into_iter()
                        .flatten()
                        .filter_map(|b| b["id"].as_str().map(str::to_owned))
                })
                .collect()
        };
        if ids(&self.windows) != ids(&next) {
            self.program_task = None;
            self.programs = json!({});
            self.directories = json!({});self.titles=json!({});self.working=json!([]);self.title_owners=json!({});self.title_events.clear();self.process_events.clear();
            self.program_refresh = tokio::time::Instant::now();
        }
        self.windows = next;
        let live: BTreeSet<_>=self.windows.as_array().into_iter().flatten().flat_map(|w|w["blocks"].as_array().into_iter().flatten()).filter_map(|b|b["id"].as_str().map(str::to_owned)).collect();
        if self.retained_floating.as_ref().is_some_and(|p|!live.contains(&p.block)) {self.retained_floating=None;}
        self.mirror.retain(|block| live.contains(block) && !minimized.contains(block));
        if !minimized.is_empty() {
            self.preview_updates.retain(|id, _| !minimized.contains(id));
        }
        let before=self.progress_reports.len();
        self.progress_reports.retain(|_,r|r["session"]!=self.selected_session || r["block"].as_str().is_some_and(|id|live.contains(id)));
        if self.progress_reports.len()!=before {self.activity_dirty=true;}
        if !self.selected_block.is_empty() && !selected_exists {
            self.data = None;
            self.data_resume = None;
            self.replica = None;
            self.screen = None;
            self.selected_block.clear();
            self.controlled = false;
            self.notice = "That terminal has closed".into();
        }
    }
    async fn refresh_view(&mut self) -> Result<()> {
        if self.selected_session.is_empty() {
            return Ok(());
        }
        let mut switched_pane = false;
        const MAX_VIEW_ATTEMPTS: usize = 3;
        for attempt in 1..=MAX_VIEW_ATTEMPTS {
            let view = self.control.as_mut().context("not connected")?
                .call("session.view", json!({"session_id":self.selected_session})).await?;
            self.set_view(&view);
            if self.retained_floating.as_ref().is_some_and(|pane| minimized_pane(&self.windows, &pane.block).is_some()) {
                self.close_retained_floating().await;
            }
            if minimized_pane(&self.windows, &self.selected_block).is_none() { break; }
            if attempt == MAX_VIEW_ATTEMPTS {
                self.open_block_stream(String::new(), false).await?;
                self.awaiting_pane_selection = true;
                self.error = "Floating panes are changing on another client. Choose a pane to continue.".into();
                switched_pane = false;
                break;
            }
            let block = visible_target(&self.windows, self.selected_block.clone());
            self.open_block_stream(block, true).await?;
            switched_pane = true;
            if self.selected_block.is_empty() { break; }
        }
        self.publish();
        if switched_pane && self.controlled {
            self.focus_selected().await?;
        }
        Ok(())
    }
    fn apply_program_details(&mut self, details: Value) {
        if !self.progress_reports.is_empty() {self.activity_dirty=true;}
        self.programs=details["programs"].clone();self.directories=details["directories"].clone();
        self.titles=details["titles"].clone();self.working=details["working"].clone();
        self.title_owners=details["owners"].clone();
        for (id,owner) in &self.process_events {
            if !same_process(&self.title_owners[id],owner) {self.titles[id]=json!("");}
            self.title_owners[id]=owner.clone();
        }
        // A slow polling response must never replace a newer streamed title frame.
        for (id,event) in &self.title_events {
            if same_process(&self.title_owners[id],&event["owner"]) {self.titles[id]=json!(display_title(event));}
        }
    }
    fn program_event(&mut self, id: &str, name: &str, payload: &Value) {
        if !self.windows.as_array().into_iter().flatten().any(|w|
            w["blocks"].as_array().into_iter().flatten().any(|b|b["id"]==id)) {return;}
        match name {
            "progress_report" => {if crate::progress::update(&mut self.progress_reports,&self.selected_session,id,payload) {self.activity_dirty=true;}},
            "process_changed" => {
                let owner=json!({"pid":payload["foreground"]["pid"],"name":payload["foreground"]["name"]});
                if !same_process(&self.title_owners[id],&owner) && self.progress_reports.remove(&crate::activity::pane_key(&self.selected_session,id)).is_some() {self.activity_dirty=true;}
                self.title_owners[id]=owner.clone();self.process_events.insert(id.into(),owner);
                if self.title_events.get(id).is_some_and(|event|!same_process(&self.title_owners[id],&event["owner"])) {
                    self.title_events.remove(id);self.titles[id]=json!("");
                }
                self.program_refresh=tokio::time::Instant::now();
            }
            "title_changed" => {
                if self.progress_reports.contains_key(&crate::activity::pane_key(&self.selected_session,id)) {self.activity_dirty=true;}
                if self.title_owners[id]["pid"].is_number() && !same_process(&self.title_owners[id],&payload["owner"]) {return;}
                self.title_events.insert(id.into(),payload.clone());
                if same_process(&self.title_owners[id],&payload["owner"]) {self.titles[id]=json!(display_title(payload));}
                if crate::activity::apply_title(&mut self.activity,&self.selected_session,id,payload) {self.activity_dirty=true;}
            }
            "child_exited" => {if self.progress_reports.remove(&crate::activity::pane_key(&self.selected_session,id)).is_some() {self.activity_dirty=true;}self.program_refresh=tokio::time::Instant::now();},
            "program_status_changed" | "program_status_removed" => self.program_refresh=tokio::time::Instant::now(),
            _=>()
        }
    }
    async fn event(&mut self, event: Value) -> Result<()> {
        let event = &event["payload"];
        if event["type"] == "session_destroyed"
            && event["payload"]["session_id"].as_str() == Some(self.selected_session.as_str())
        {
            self.clear_ended_session(self.selected_session.clone());
        }
        if matches!(
            event["type"].as_str(),
            Some("session_created" | "session_destroyed" | "session_changed")
        ) {
            self.activity_full=true;
            self.refresh_session_catalog().await?;
        }
        if event["type"] == "session_event" {
            let inner = &event["payload"]["event"];
            if inner["type"]=="session_label_changed" {
                self.sessions=self.control.as_mut().context("not connected")?.call("session.list",json!({})).await?["sessions"].clone();
                self.activity_full=true;
            }
            if inner["type"]=="block_event" {
                let block=&inner["payload"];let sid=event["payload"]["session_id"].as_str().unwrap_or("");
                if block["creator_name"]=="com.superlogical.terminal" && let (Some(id),Some(name))=(block["block_id"].as_str(),block["name"].as_str()) {
                    if name=="progress_report" {
                        if crate::progress::update(&mut self.progress_reports,sid,id,&block["payload"]) {self.activity_dirty=true;self.publish();}
                        return Ok(());
                    }
                    if (name=="child_exited" || (name=="process_changed" && sid!=self.selected_session)) && self.progress_reports.remove(&crate::activity::pane_key(sid,id)).is_some() {self.activity_dirty=true;self.publish();}
                    if matches!(name,"program_status_changed"|"program_status_removed"|"process_changed"|"child_exited") {
                        let key=crate::activity::pane_key(sid,id);
                        if self.activity_panes.as_array().into_iter().flatten().any(|p|
                            p["session"]["session_id"]==sid && p["block"]["block_id"]==id) {self.activity_pending.insert(key);} else {self.activity_full=true;}
                    }
                    if name=="title_changed" {
                        self.activity_titles.insert(crate::activity::pane_key(sid,id),json!({"session":sid,"block":id,"event":block["payload"]}));
                        if self.activity_titles.len()>1024 {if let Some(key)=self.activity_titles.keys().next().cloned() {self.activity_titles.remove(&key);}}
                    }
                    if sid==self.selected_session {
                        self.program_event(id,name,&block["payload"]);
                        if matches!(name,"title_changed"|"process_changed"|"program_status_changed"|"program_status_removed"|"child_exited") {self.publish();}
                    } else if name=="title_changed" && crate::activity::apply_title(&mut self.activity,sid,id,&block["payload"]) {
                        self.activity_dirty=true;self.publish();
                    }
                }
                return Ok(());
            }
            if inner["type"]=="session_view_changed" || inner["type"]=="block_closed" {self.activity_full=true;}
            if matches!(
                inner["type"].as_str(),
                Some("session_view_changed" | "block_owner_changed" | "block_closed")
            ) {
                self.refresh_close_view().await?;
            }
        }
        self.publish();
        Ok(())
    }
    fn update_apcs(&mut self) {
        let Some(replica) = self.replica.as_mut() else {
            return;
        };
        let events: Vec<_>=replica.events.drain(..).collect();
        let clipboard=replica.clipboard_write.take();
        let changed=!events.is_empty() || clipboard.is_some();
        if let Some(text)=clipboard {
            self.clipboard_serial+=1;
            self.clipboard=json!({"serial":self.clipboard_serial,"text":text,"block":self.selected_block,"connection":self.connection_epoch,"navigation":self.navigation_id});
        }
        for event in events {
            // Data and control streams have independent delivery order. Resize APCs
            // still update the grid in Replica, but cannot override canonical ownership.
            let id=self.selected_block.clone();
            if let Some(name)=event["type"].as_str() {self.program_event(&id,name,&event);}
            if event["type"] == "child_exited" {self.notice=format!("Program exited ({})",event["exit_code"]);}
        }
        if changed {self.publish();}
    }

    fn input_target(&self)->Target {
        Target {session:self.selected_session.clone(),block:self.selected_block.clone(),navigation:self.navigation_id,connection:self.connection_epoch}
    }
    fn command_error(&mut self,error:anyhow::Error,navigation:Option<u64>,input:bool) {
        let superseded=navigation.is_some_and(|id|id<self.latest_navigation.load(Ordering::Acquire));
        if !superseded {if let Some(id)=navigation {self.navigation_id=self.navigation_id.max(id);}}
        self.creating=false;
        let message=error.to_string();
        if message.contains("timed out") || message.contains("WebSocket") {
            self.recover(error);
            if input {self.notice="Request outcome is uncertain; input was not resent".into();self.publish();}
        } else if !superseded {self.error=message;self.publish();}
    }
    async fn execute_command(&mut self,value:Value) {
        let navigation=value["navigationId"].as_u64();
        // Only replaceable navigation/control work is skipped. Mutations, input,
        // paste and release retain their original ordering and target checks.
        if matches!(value["type"].as_str(),Some("block"|"session"|"take"|"resize")) &&
            navigation.is_some_and(|id|id<self.latest_navigation.load(Ordering::Acquire)) {return;}
        let tab_move=if value["type"]=="move_tab" {value["requestId"].as_u64()} else {None};
        let pane_move=if value["type"]=="move_pane" {value["requestId"].as_u64()} else {None};
        if let Err(error)=self.command(value).await {self.command_error(error,navigation,false);}
        // Acknowledge failures too, so an optimistic order can roll back without retrying a mutation.
        if let Some(id)=tab_move {self.tab_move_id=id;self.publish();}
        if let Some(id)=pane_move {self.pane_move_id=id;self.publish();}
    }
    async fn send_input(&mut self,value:Value)->Result<()> {
        ensure!(!self.monitoring,"Background monitor is read-only");
        if value["type"]=="wheel" && value["mirror"]==true {
            return self.send_mirror_wheel(&value).await;
        }
        let target=self.input_target();
        if !target.matches(&value) {return Ok(());}
        if value["type"]=="wheel" {
            return self.send_wheel(&value,target).await;
        }
        if value["type"]=="click" && (!self.visible || self.paused || self.phase!="connected" || !self.controlled) {return Ok(());}
        ensure!(self.visible && !self.paused && self.phase=="connected" && self.controlled,"Take control before typing");
        let click=value["type"]=="click";
        let encoded;
        let text=if click {
            let Some(replica)=self.replica.as_mut().filter(|r|r.ready()) else{return Ok(());};
            encoded=String::from_utf8(replica.terminal()?.click(
                value["col"].as_i64().unwrap_or(0).clamp(0,65534) as i32,
                value["row"].as_i64().unwrap_or(0).clamp(0,65534) as i32,
                value["cw"].as_u64().unwrap_or(1).clamp(1,1024) as u32,
                value["ch"].as_u64().unwrap_or(1).clamp(1,1024) as u32)?).context("Legacy mouse coordinates cannot be sent as UTF-8 terminal input")?;
            if encoded.is_empty() {return Ok(());}
            encoded.as_str()
        } else {value["text"].as_str().context("text required")?};
        // Render only if typing actually moved the viewport. An unconditional render here
        // publishes an unchanged frame and starts the 16 ms render throttle just before
        // the echo arrives, so every echo waited a full frame interval.
        let scrolled_back=self.screen.as_ref().is_some_and(|f|f.scroll_offset+f.scroll_visible<f.scroll_total);
        if !click && let Some(replica)=self.replica.as_mut().filter(|r|r.ready()) {replica.terminal()?.scroll_to_bottom();if scrolled_back {self.dirty=true;}}
        // Unlike terminal.write, block.event is serialized by Rex on this connection.
        // An unidentified key with text preserves our already-encoded input bytes;
        // it bypasses Lua bindings, matching the previous raw-write behavior.
        self.start_input(target,value["type"]=="paste",text).await
    }
    async fn start_input(&mut self,target:Target,paste:bool,text:&str)->Result<()> {
        let payload=json!({"session_id":target.session,"block_id":target.block,"event":input_event(paste,text)});
        let reply=self.control.as_mut().context("not connected")?.start_call("block.event",payload).await?;
        self.input_acks.push_back(InputAck {target,paste,reply:Box::pin(reply.receive())});
        Ok(())
    }
    fn mirror_scroll_block<'a>(&self, value: &'a Value) -> Option<&'a str> {
        let block = value["block"].as_str()?;
        if !self.visible || self.paused || self.phase != "connected"
            || value["session"] != self.selected_session || block == self.selected_block
            || value["navigationId"].as_u64() != Some(self.navigation_id)
            || value["connectionEpoch"].as_u64() != Some(self.connection_epoch)
            || self.navigation_id < self.latest_navigation.load(Ordering::Acquire)
            || !self.mirror.wants(&self.selected_session, block) {
            return None;
        }
        let window = pane_window(&self.windows, &self.selected_block)?;
        let zoomed = window["zoomed"].as_str().unwrap_or("");
        (window["id"] == value["window"] && (zoomed.is_empty() || zoomed == block)).then_some(block)
    }

    async fn send_mirror_wheel(&mut self, value: &Value) -> Result<()> {
        let Some(block) = self.mirror_scroll_block(value) else { return Ok(()); };
        let lines = crate::input_queue::wheel_lines(value) as i32;
        if lines == 0 || !self.owned_blocks.contains(block) {
            return Ok(());
        }
        let Some(terminal) = self.mirror.owned_terminal(block)? else { return Ok(()); };
        match wheel_text(terminal, value, lines)? {
            Some(text) if !text.is_empty() => {
                let mut target = self.input_target();
                target.block = block.to_owned();
                self.start_input(target, false, &text).await
            }
            None => self.mirror.scroll(block, Some(lines)),
            _ => Ok(()),
        }
    }

    async fn send_wheel(&mut self,value:&Value,target:Target)->Result<()> {
        if !self.visible || self.paused || self.phase!="connected"
            || target.navigation<self.latest_navigation.load(Ordering::Acquire) {return Ok(());}
        let lines=crate::input_queue::wheel_lines(value) as i32;
        if lines==0 {return Ok(());}
        let Some(replica)=self.replica.as_mut().filter(|r|r.ready()) else{return Ok(());};
        let terminal=replica.terminal()?;
        let Some(text)=wheel_text(terminal,value,lines)? else {
            terminal.scroll_viewport(lines);
            self.dirty=true;
            return Ok(());
        };
        if text.is_empty(){return Ok(());}
        if !self.controlled {
            self.take_selected().await?;
            if target!=self.input_target() || target.navigation<self.latest_navigation.load(Ordering::Acquire) {return Ok(());}
            if let (Some(cols),Some(rows))=(value["columns"].as_u64(),value["rows"].as_u64())
                && cols>0 && rows>0 {
                self.control.as_mut().context("not connected")?.terminal(&target.session,&target.block,"resize",
                    json!({"columns":cols.clamp(1,512),"rows":rows.clamp(1,256)})).await?;
            }
        }
        if self.controlled && target.navigation>=self.latest_navigation.load(Ordering::Acquire) {
            self.start_input(target,false,&text).await?;
        }
        Ok(())
    }
    async fn command(&mut self, value: Value) -> Result<()> {
        let kind=value["type"].as_str().context("command type missing")?;
        ensure!(!self.monitoring || matches!(kind,"monitor"|"connect"|"disconnect"|"network"|"retry"|"activity_seen"),"Background monitor is read-only");
        match kind {
            "monitor" => {
                // Irreversible for this handle. Must be enabled before connecting or selecting a pane.
                ensure!(self.endpoint.is_none() && self.selected_session.is_empty() && self.data.is_none(),"Enable monitoring before connecting");
                self.monitoring=true;self.visible=false;self.activity_open=false;
                self.publish();
            }
            "connect" => {
                let server = value["server"]
                    .as_str()
                    .context("server required")?
                    .trim()
                    .to_owned();
                ensure!(
                    server.starts_with("https://")
                        || server.starts_with("http://")
                        || server.starts_with("wss://")
                        || server.starts_with("ws://")
                        || server.starts_with("ssh://"),
                    "Enter a complete HTTP(S), WS(S), or SSH server URL"
                );
                let endpoint = Endpoint::new(&server, Duration::from_secs(10))?;
                self.disconnect().await;
                self.endpoint = Some(endpoint);
                self.server = server;
                self.selected_session.clear();
                self.selected_block.clear();
                self.windows = json!([]);
                self.screen = None;
                self.replica = None;
                self.paused = false;
                self.phase = "connecting".into();
                self.error.clear();
                self.notice.clear();
                self.publish();
                self.retry=self.network_online.then(tokio::time::Instant::now);
            }
            "disconnect" => {
                self.disconnect().await;
                self.endpoint = None;
                self.replica = None;
                self.screen = None;
                self.phase = "disconnected".into();
                self.error.clear();
                self.notice.clear();
                self.selected_block.clear();
                self.selected_session.clear();
                self.windows = json!([]);
                self.sessions = json!([]);
                self.publish();
            }
            "retry" | "network" => {
                let network=value["type"]=="network";
                if network {self.network_online=value["online"].as_bool().unwrap_or(true);}
                if self.paused || self.endpoint.is_none() {return Ok(());}
                // Android's default route may briefly disappear or change underneath
                // an established VPN/TCP socket. A route callback is not a socket
                // failure: keep the existing channels, ownership and in-flight input.
                // Actual I/O failure still goes through recover(); availability then
                // controls when a replacement handshake can start.
                if network && self.phase=="connected" {return Ok(());}
                self.lose_connections();self.backoff=1;self.phase="reconnecting".into();
                self.retry=self.network_online.then(tokio::time::Instant::now);self.error.clear();
                self.notice=if self.network_online {"Reconnecting; input is paused"} else {"Waiting for network; input is paused"}.into();
                self.publish();
            }
            "activity" => {
                self.activity_open=value["open"].as_bool().unwrap_or(false);
                if self.activity_open {self.activity_full=true;self.activity_refresh=tokio::time::Instant::now();}
                self.publish();
            }
            "activity_seen" => {
                if let Some(items)=value["items"].as_object() {
                    self.activity_seen=json!(items.iter().take(2048).filter(|(k,v)|k.len()<=1024 && v.as_str().is_some_and(|v|v.len()<=4096)).map(|(k,v)|(k.clone(),v.clone())).collect::<serde_json::Map<_,_>>());
                    self.attention_count=crate::activity::attention_count(&self.activity,&self.activity_seen);self.publish();
                }
            }
            "peek" => {
                if self.phase != "connected"
                    || self.paused
                    || value["session"].as_str() != Some(self.selected_session.as_str())
                {
                    return Ok(());
                }
                let ids: Vec<String> = value["ids"]
                    .as_array()
                    .into_iter()
                    .flatten()
                    .filter_map(Value::as_str)
                    .filter(|id| pane_window(&self.windows,id).is_some() && minimized_pane(&self.windows,id).is_none())
                    .take(2)
                    .map(str::to_owned)
                    .collect();
                if !ids.is_empty() {
                    self.preview_task = Some(preview_task(
                        self.endpoint.clone().context("no server")?,
                        self.selected_session.clone(),
                        ids,
                    ));
                }
            }
            "sessions_info" => {
                if self.phase == "connected" {
                    let sessions = self.sessions.clone();
                    let own = self
                        .control
                        .as_ref()
                        .and_then(Control::client_id)
                        .unwrap_or("")
                        .to_owned();
                    self.summary_task = Some(summary_task(
                        self.endpoint.clone().context("no server")?,
                        sessions,
                        own,
                    ));
                }
            }
            "pane_catalog" => {
                if value["open"]==false {
                    self.pane_catalog_task=None;
                    self.pane_catalog=Value::Null;
                    self.publish();
                } else if self.phase=="connected" && self.pane_catalog_task.is_none() {
                    self.pane_catalog_task=Some(pane_catalog_task(self.endpoint.clone().context("no server")?));
                    self.publish();
                }
            }
            "rename_session" => {
                ensure!(self.phase=="connected" && !self.paused && !self.creating,"Connect before renaming a session");
                let id=value["id"].as_str().filter(|id|!id.is_empty()).context("session ID required")?;
                let label=value["label"].as_str().context("session name required")?.trim();
                ensure!(!label.is_empty(),"Enter a session name");
                ensure!(label.chars().count()<=128,"Use a session name of at most 128 characters");
                ensure!(!label.chars().any(char::is_control),"Session names cannot contain control characters");
                self.creating=true;self.error.clear();self.publish();
                // Attach a separate control client; renaming another session must
                // not move the viewed session, own a pane, or resize its PTY.
                let mut editor=Control::connect(self.endpoint.clone().context("no server")?,None,Trace::default()).await?;
                editor.call("client.set_label",json!({"label":"rex-android-rename"})).await?;
                let result=async {
                    editor.attach(id).await?;
                    editor.call("session.set_label",json!({"session_id":id,"label":label})).await?;
                    Ok::<(),anyhow::Error>(())
                }.await;
                let _=editor.close().await;result?;
                self.sessions=self.control.as_mut().context("not connected")?.call("session.list",json!({})).await?["sessions"].clone();
                self.activity_full=true;self.creating=false;self.notice="Session renamed".into();self.publish();
            }
            "close_session" => {
                ensure!(self.phase=="connected" && !self.paused && !self.creating,"Connect before closing a session");
                let id=value["id"].as_str().context("session ID required")?.to_owned();
                ensure!(!id.is_empty(),"session ID required");
                self.creating=true;self.error.clear();self.publish();
                // Rex requires attachment to destroy. A short-lived client avoids
                // moving the viewed session or borrowing another block's claim.
                let mut closer=Control::connect(self.endpoint.clone().context("no server")?,None,Trace::default()).await?;
                closer.call("client.set_label",json!({"label":"rex-android-close"})).await?;
                let result=async {
                    closer.attach(&id).await?;
                    closer.call("session.destroy",json!({"session_id":id})).await?;
                    Ok::<(),anyhow::Error>(())
                }.await;
                let _=closer.close().await;
                if let Err(error)=result
                    && error.downcast_ref::<rex_core::control::RpcError>().is_none_or(|e| e.code!="session_not_found") {return Err(error);}
                if self.selected_session == id {
                    self.clear_ended_session(id);
                }
                self.refresh_session_catalog().await?;
                self.creating=false;self.notice="Session closed".into();self.publish();
            }
            "new_session" => {
                ensure!(
                    self.phase == "connected" && !self.paused,
                    "Connect before creating a session"
                );
                self.creating = true;
                self.publish();
                let label = value["label"].as_str().unwrap_or("").trim();
                ensure!(
                    label.chars().count() <= 128,
                    "Use a session name of at most 128 characters"
                );
                let result=self.control.as_mut().context("not connected")?.call("session.create",json!({"label":label,"initial_windows":[{"window_label":"shell","layout":{"block":{"flavor":"com.superlogical.terminal.shell"}}}]})).await?;
                let id = result["session_id"]
                    .as_str()
                    .context("No session ID")?
                    .to_owned();
                let block = result["initial_windows"][0]["block_ids"][0]
                    .as_str()
                    .context("No shell block")?
                    .to_owned();
                self.release_session_panes().await?;
                if let Some(mut data) = self.data.take() {
                    let _ = data.close().await;
                }
                if !self.selected_session.is_empty() {
                    let _ = self
                        .control
                        .as_mut()
                        .unwrap()
                        .call(
                            "session.detach",
                            json!({"session_id":self.selected_session}),
                        )
                        .await;
                }
                let attached = self.control.as_mut().unwrap().attach(&id).await?;
                self.undo_close = None;
                self.selected_session = id;
                self.selected_block.clear();
                self.awaiting_pane_selection = false;
                self.set_view(&attached["view"]);
                self.sessions = self
                    .control
                    .as_mut()
                    .unwrap()
                    .call("session.list", json!({}))
                    .await?["sessions"]
                    .clone();
                self.open_block(block).await?;
                self.creating = false;
                self.publish();
            }
            "rename_tab" | "rename_pane" | "float_pane" | "dock_pane" | "resize_floating" | "minimize_floating" | "place_pane" => {
                ensure!(self.visible && !self.paused && self.phase=="connected" && !self.creating,"Connect before editing a terminal");
                self.refresh_view().await?;
                let (method,args)=terminal_edit_call(&self.windows,&self.selected_session,&value)?;
                self.error.clear();
                // Send once: never repeat a layout mutation after an uncertain reply.
                let control = self.control.as_mut().context("not connected")?;
                if method == "session.set_layer_state" {
                    ensure!(control.welcome()["api_revision"].as_u64().is_some_and(|revision| revision >= 9),
                        "Update this Rex server to sync floating panes");
                }
                control.call(method,args).await?;
                self.refresh_view().await?;
                if value["type"]=="float_pane" && value["focus"]==true {
                    let block=value["id"].as_str().context("pane ID required")?.to_owned();
                    self.open_block_with_layout(block,true).await?;
                }
            }
            "move_tab" => {
                ensure!(self.visible && !self.paused && self.phase=="connected" && !self.creating,"Connect before moving tabs");
                ensure!(value["session"].as_str()==Some(self.selected_session.as_str()),"The selected session changed");
                let id=value["id"].as_str().context("tab ID required")?;
                let before=value["before"].as_str().filter(|s|!s.is_empty());
                let exists=|id:&str|self.windows.as_array().into_iter().flatten().any(|w|w["id"]==id);
                ensure!(exists(id) && before.is_none_or(exists),"That tab is no longer available");
                ensure!(before!=Some(id),"A tab cannot be moved before itself");
                let mut params=json!({"session_id":self.selected_session,"window_id":id});
                if let Some(before)=before {params["before_window_id"]=json!(before);}
                self.error.clear();
                self.control.as_mut().context("not connected")?.call("session.move_window",params).await?;
                // Moving a window never opens/resizes a terminal or changes focus/ownership.
                self.refresh_view().await?;
            }
            "move_pane" => {
                ensure!(self.visible && !self.paused && self.phase=="connected" && !self.creating,"Connect before moving panes");
                ensure!(value["session"].as_str()==Some(self.selected_session.as_str()),"The selected session changed");
                self.refresh_view().await?;
                let swaps=pane_reorder_swaps(&self.windows,&value)?;
                self.error.clear();
                for (block,other) in swaps {
                    let result=self.control.as_mut().context("not connected")?.call("session.swap_blocks",
                        json!({"session_id":self.selected_session,"block_id":block,"other_block_id":other})).await;
                    if let Err(error)=result {
                        // Never replay a swap: it could undo an already-applied move.
                        let _=self.refresh_view().await;
                        let message=format!("Pane order may be partially updated; check the layout before trying again: {error:#}");
                        return Err(error.context(message));
                    }
                }
                // Swaps preserve split geometry and programs. Do not open, own or resize a pane.
                self.refresh_view().await?;
            }
            "session" => {
                let session = value["id"]
                    .as_str()
                    .context("session ID required")?
                    .to_owned();
                if self.selected_session != session {
                    self.undo_close = None;
                    self.preview_task = None;
                    self.summary_task = None;
                    self.program_task = None;
                    self.programs = json!({});
                    self.directories = json!({});self.titles=json!({});self.working=json!([]);self.title_owners=json!({});self.title_events.clear();self.process_events.clear();
                    self.program_refresh = tokio::time::Instant::now();
                    self.preview_updates.clear();
                    self.release_session_panes().await?;
                    if let Some(mut data) = self.data.take() {
                        let _ = data.close().await;
                    }
                    self.close_retained_floating().await;
                    self.data_resume = None;
                    self.replica = None;
                    self.screen = None;
                    self.selected_block.clear();
                    if !self.selected_session.is_empty() {
                        self.control.as_mut().context("not connected")?
                            .call("session.detach", json!({"session_id":self.selected_session})).await?;
                    }
                }
                self.navigation_id = value["navigationId"].as_u64().unwrap_or(0);
                let attached = match self.control.as_mut().context("not connected")?.attach(&session).await {
                    Ok(attached) => attached,
                    Err(error) if missing_terminal(&error) => {
                        self.clear_ended_session(session);
                        self.refresh_session_catalog().await?;
                        self.publish();
                        return Ok(());
                    }
                    Err(error) => return Err(error),
                };
                self.selected_session = session;
                self.awaiting_pane_selection = false;
                self.set_view(&attached["view"]);
                self.error.clear();
                if let Some(block)=value["block"].as_str() {
                    ensure!(self.windows.as_array().into_iter().flatten().any(|w|w["blocks"].as_array().into_iter().flatten().any(|b|b["id"]==block)),"This pane is no longer available. Refresh activity.");
                    if value["restoreMinimized"] == true { self.restore_minimized(block).await?; }
                    self.open_block(block.to_owned()).await?;
                }
                self.publish();
            }
            "block" => {
                self.navigation_id = value["navigationId"].as_u64().unwrap_or(0);
                let block = value["id"]
                    .as_str()
                    .context("block ID required")?
                    .to_owned();
                if value["restoreMinimized"] == true { self.restore_minimized(&block).await?; }
                self.open_block_with_layout(block,value["keepTiled"]==true).await?;
            }
            "close_tab" | "close_pane" => {
                ensure!(self.phase=="connected" && !self.paused && !self.creating,"Connect before closing a terminal");
                ensure!(value["session"].as_str()==Some(self.selected_session.as_str()),"The selected session changed; review the terminal before closing it");
                let id=value["id"].as_str().filter(|s|!s.is_empty()).context("terminal ID required")?.to_owned();
                let tab=value["type"]=="close_tab";
                let old_window=self.windows.as_array().into_iter().flatten().position(|w|w["blocks"].as_array().into_iter().flatten().any(|b|b["id"]==self.selected_block)).unwrap_or(0);
                let old_pane=if tab {0} else {self.windows[old_window]["blocks"].as_array().into_iter().flatten().position(|b|b["id"]==self.selected_block).unwrap_or(0)};
                self.creating=true;self.error.clear();self.publish();
                if !self.refresh_close_view().await? {self.creating=false;self.notice="Terminal already closed".into();self.publish();return Ok(());}
                let exists=if tab {self.windows.as_array().into_iter().flatten().any(|w|w["id"]==id)}
                    else {self.windows.as_array().into_iter().flatten().any(|w|w["blocks"].as_array().into_iter().flatten().any(|b|b["id"]==id))};
                if exists {
                    if tab {
                        let window=self.windows.as_array().unwrap().iter().find(|w|w["id"]==id).unwrap();
                        let actual: Vec<_>=window["blocks"].as_array().into_iter().flatten().map(|b|b["id"].clone()).collect();
                        ensure!(value["blocks"].as_array()==Some(&actual),"This tab changed while you were confirming. Review it before closing");
                    }
                    let (method,mut args)=if tab {("session.close_window",json!({"session_id":self.selected_session,"window_id":id}))}
                        else {("block.close",json!({"session_id":self.selected_session,"block_id":id}))};
                    let expires=tokio::time::Instant::now()+UNDO_CLOSE_WINDOW;
                    if self.supports_undo_close {args["undoable"]=json!(true);}
                    match self.control.as_mut().context("not connected")?.call(method,args).await {
                        Ok(_) if self.supports_undo_close => {
                            self.undo_close_serial+=1;
                            self.undo_close=Some(UndoClose {serial:self.undo_close_serial,tab,expires});
                        }
                        Ok(_) => {}
                        Err(error) if missing_terminal(&error) => {}
                        Err(error) => return Err(error),
                    }
                }
                if self.refresh_close_view().await? && self.selected_block.is_empty() {
                    let windows=self.windows.as_array().context("invalid window list")?;
                    let fallback=windows.get(old_window).or_else(||windows.last()).and_then(|w|w["blocks"].as_array())
                        .and_then(|blocks|blocks.get(old_pane).or_else(||blocks.last())).and_then(|b|b["id"].as_str()).map(str::to_owned);
                    if let Some(block)=fallback {self.open_block(block).await?;}
                }
                self.creating=false;self.notice=if tab {"Tab closed"} else {"Pane closed"}.into();self.publish();
            }
            "undo_close" => {
                if !self.visible || self.phase!="connected" || self.paused || self.creating
                    || value["session"]!=self.selected_session
                    || value["connectionEpoch"].as_u64()!=Some(self.connection_epoch)
                    || value["navigationId"].as_u64()!=Some(self.navigation_id)
                    || self.navigation_id<self.latest_navigation.load(Ordering::Acquire)
                    || !self.undo_close.as_ref().is_some_and(|close|
                        value["serial"].as_u64()==Some(close.serial) && tokio::time::Instant::now()<close.expires) {
                    return Ok(());
                }
                self.undo_close=None;
                self.creating=true;self.error.clear();self.publish();
                let result=self.control.as_mut().context("not connected")?.call("client.undo_close",json!({})).await;
                if let Err(error)=&result
                    && error.downcast_ref::<rex_core::control::RpcError>().is_some_and(|rpc|rpc.code=="not_found") {
                    anyhow::bail!("This close can no longer be undone.");
                }
                let restored=result?;
                if restored["session_id"]==self.selected_session
                    && self.navigation_id>=self.latest_navigation.load(Ordering::Acquire)
                    && self.refresh_close_view().await? {
                    if let Some(block)=restored["block_id"].as_str() {
                        self.open_block(block.to_owned()).await?;
                    }
                }
                self.creating=false;self.publish();
            }
            "new_tab" | "new_pane" => {
                ensure!(
                    self.phase == "connected" && !self.paused && !self.creating,
                    "Connect before creating a terminal"
                );
                ensure!(!self.selected_session.is_empty(), "Choose a session first");
                ensure!(
                    value["session"].as_str() == Some(self.selected_session.as_str()),
                    "The selected session changed; try again"
                );
                let layout = json!({"block":{"flavor":"com.superlogical.terminal.shell"}});
                let (method, args) = if value["type"] == "new_tab" {
                    (
                        "session.new_window",
                        json!({"session_id":self.selected_session,"window_label":format!("Terminal {}", self.windows.as_array().map_or(0, Vec::len) + 1),"layout":layout,"focus":false}),
                    )
                } else {
                    let anchor=split_anchor(&self.windows,&value)?;
                    (
                        "session.new_split",
                        new_split_arguments(&self.selected_session,anchor,&value)?,
                    )
                };
                self.creating = true;
                self.error.clear();
                self.publish();
                // Creation is sent once. A lost reply must never duplicate a shell.
                let result = self
                    .control
                    .as_mut()
                    .context("not connected")?
                    .call(method, args)
                    .await?;
                let block = result["block_ids"][0]
                    .as_str()
                    .context("Server created no terminal block")?
                    .to_owned();
                let keep_tiled=value["type"]=="new_pane" && value["keepTiled"]==true;
                if keep_tiled {self.refresh_view().await?;}
                self.open_block_with_layout(block,keep_tiled).await?;
                self.creating = false;
                self.publish();
            }
            "theme" => {
                let args = value["args"].clone();
                ensure!(args.is_object(), "theme arguments required");
                ensure!(
                    matches!(args["scheme"].as_str(), Some("light" | "dark")),
                    "invalid theme scheme"
                );
                self.theme = Some(args);
                self.apply_theme().await?;
            }
            "take" => {
                if value["session"].is_string() && (!self.visible || self.paused || self.phase!="connected"
                    || value["session"]!=self.selected_session || value["block"]!=self.selected_block
                    || value["navigationId"].as_u64()!=Some(self.navigation_id)) {return Ok(());}
                self.take_selected().await?;
                if let (Some(columns),Some(rows))=(value["columns"].as_u64(),value["rows"].as_u64())
                    && columns>0 && rows>0 {
                    self.control.as_mut().context("not connected")?.terminal(&self.selected_session,&self.selected_block,"resize",
                        json!({"columns":columns.clamp(1,512),"rows":rows.clamp(1,256)})).await?;
                }
            }
            "zoom" => {
                // Zoom a pane to fill its window, or restore the layout, for every client.
                ensure!(value["session"]==self.selected_session,"Session changed");
                let block=value["block"].as_str().filter(|b|!b.is_empty()).context("block required")?;
                let zoom=value["zoom"].as_bool().context("zoom required")?;
                self.control.as_mut().context("not connected")?.call("session.zoom_block",json!({"session_id":self.selected_session,
                    "block_id":block,"zoom":zoom})).await?;
            }
            "resize_split" => {
                // Dragging a pane border in the multi-pane layout.
                ensure!(value["session"]==self.selected_session,"Session changed");
                let direction=value["direction"].as_str().filter(|d|matches!(*d,"horizontal"|"vertical")).context("direction required")?;
                let ratio=value["ratio"].as_f64().filter(|r|r.is_finite()).context("ratio required")?.clamp(0.05,0.95);
                self.control.as_mut().context("not connected")?.call("session.resize_pane",json!({"session_id":self.selected_session,
                    "direction":direction,"before_block_id":value["before"],"after_block_id":value["after"],"ratio":ratio})).await?;
            }
            "mirror_obscured" => {
                let obscured = value["obscured"].as_bool().context("Missing mirror visibility")?;
                self.mirror.set_obscured(obscured);
            }
            "mirror" => {
                let (session,wanted)=crate::mirror::parse(&value);
                if !self.visible || session != self.selected_session {
                    return Ok(());
                }
                let Some(window)=pane_window(&self.windows,&self.selected_block).filter(|w|w["id"]==value["window"])
                    else {return Ok(());};
                let zoomed=window["zoomed"].as_str().filter(|id|!id.is_empty());
                let wanted: Vec<_> = wanted.into_iter().filter(|want| {
                    want.block != self.selected_block && zoomed.is_none_or(|id|id==want.block) &&
                        pane_layout(window).any(|pane|pane["id"]==want.block && pane["minimized"]!=true)
                }).collect();
                if self.retained_floating.as_ref().is_some_and(|pane|wanted.iter().any(|want|want.block==pane.block))
                    && let Some(pane)=self.retained_floating.take() {
                    self.mirror.adopt(&session,pane.block,pane.data,pane.replica,false);
                }
                self.mirror.request(&session, wanted);
                self.publish_mirror_status();
            }
            "release" => {
                self.release_selected().await?;
                self.other_owner=false;
                self.notice = "Viewing terminal".into();
                self.publish();
            }
            "copy_semantic" => {
                if !self.visible || self.paused || !self.input_target().matches(&value) {return Ok(());}
                let Some(replica)=self.replica.as_mut().filter(|r|r.ready()) else{return Ok(());};
                let terminal=replica.terminal()?;let frame=terminal.frame()?;
                ensure!(value["columns"].as_u64()==Some(frame.columns as u64) && value["rows"].as_u64()==Some(frame.rows as u64) &&
                    value["scrollTotal"].as_u64()==Some(frame.scroll_total) && value["scrollOffset"].as_u64()==Some(frame.scroll_offset) &&
                    value["viewport"].as_str()==Some(frame.text().as_str()),"Terminal changed. Select the text again.");
                let col=u16::try_from(value["col"].as_u64().context("selection column missing")?)?;
                let row=u16::try_from(value["row"].as_u64().context("selection row missing")?)?;
                ensure!(col<frame.columns && row<frame.rows,"Selection is outside the terminal");
                let text=terminal.command_text(col,row,value["output"]==true)?;
                if let Some(text)=text.filter(|text|!text.is_empty()) {
                    self.clipboard_serial+=1;
                    self.clipboard=json!({"serial":self.clipboard_serial,"text":text,"block":self.selected_block,
                        "connection":self.connection_epoch,"navigation":self.navigation_id,"local":true});
                } else {self.notice="Command boundaries are not available for this selection".into();}
                self.publish();
            }
            "scroll_prompt" => {
                if !self.visible || self.paused || value["block"].as_str()!=Some(self.selected_block.as_str()) ||
                    value["session"].as_str()!=Some(self.selected_session.as_str()) ||
                    value["navigationId"].as_u64()!=Some(self.navigation_id) ||
                    value["connectionEpoch"].as_u64()!=Some(self.connection_epoch) {return Ok(());}
                if let Some(replica)=self.replica.as_mut().filter(|r|r.ready()) {
                    replica.terminal()?.scroll_prompts(value["direction"].as_i64().unwrap_or(0).clamp(-1,1) as i32);
                    self.dirty=true;
                }
            }
            "scroll" => {
                let lines = (value["bottom"] != true).then(|| value["lines"].as_i64().unwrap_or(0).clamp(-100000, 100000) as i32);
                if value["mirror"] == true {
                    if let Some(block) = self.mirror_scroll_block(&value) {
                        self.mirror.scroll(block, lines)?;
                    }
                    return Ok(());
                }
                if value["block"].as_str() != Some(self.selected_block.as_str()) {
                    return Ok(());
                }
                if let Some(replica) = self.replica.as_mut().filter(|r| r.ready()) {
                    let terminal = replica.terminal()?;
                    match lines {
                        Some(lines) => terminal.scroll_viewport(lines),
                        None => terminal.scroll_to_bottom(),
                    }
                    self.dirty = true;
                }
            }
            "input" | "paste" | "resize" => {
                if value["type"]=="resize" && value["session"].is_string() && (!self.visible || self.paused
                    || value["session"]!=self.selected_session || value["block"]!=self.selected_block
                    || value["navigationId"].as_u64()!=Some(self.navigation_id)) {return Ok(());}
                // A deferred layout resize can arrive after a legitimate ownership
                // change. It is safe to drop; typing must still be explicitly rejected.
                if value["type"]=="resize" && !self.controlled {return Ok(());}
                ensure!(self.controlled, "Take control before typing");
                let kind = value["type"].as_str().unwrap();
                if kind != "resize"
                    && let Some(replica) = self.replica.as_mut().filter(|r| r.ready())
                {
                    replica.terminal()?.scroll_to_bottom();
                    self.dirty = true;
                }
                let c = self.control.as_mut().context("not connected")?;
                let result=match kind {
                    "input"|"paste"=>c.call("block.event",json!({"session_id":self.selected_session,"block_id":self.selected_block,"event":input_event(kind=="paste",value["text"].as_str().context("text required")?)})).await,
                    _=>{
                        let cols=value["columns"].as_u64().context("columns required")?.clamp(1,512);
                        let rows=value["rows"].as_u64().context("rows required")?.clamp(1,256);
                        c.terminal(&self.selected_session,&self.selected_block,"resize",json!({"columns":cols,"rows":rows})).await
                    }
                };
                if let Err(e) = result {
                    // A lost reply does not prove the server did not type the text.
                    // Reconnect, but never automatically replay input or paste.
                    self.recover(e);
                    self.notice = "Request outcome is uncertain; input was not resent".into();
                    self.publish();
                }
            }
            "pause" => {
                // Always drop connections after this bounded explicit-release attempt.
                let _=tokio::time::timeout(Duration::from_secs(2),self.release_session_panes()).await;
                self.paused = true;
                self.retry = None;
                self.lose_connections();
                if self.endpoint.is_some() {
                    self.phase = "paused".into();
                }
                self.publish();
            }
            "resume" => {
                self.paused = false;
                if self.endpoint.is_some() && self.control.is_none() {
                    self.retry = self.network_online.then(tokio::time::Instant::now);
                    self.phase = "reconnecting".into();
                    self.publish();
                }
            }
            "refresh" => {
                self.sessions = self
                    .control
                    .as_mut()
                    .context("not connected")?
                    .call("session.list", json!({}))
                    .await?["sessions"]
                    .clone();
                self.refresh_view().await?;
                self.publish();
            }
            "preview" => {
                self.disconnect().await;
                self.endpoint = None;
                self.selected_session.clear();
                self.selected_block.clear();
                self.sessions = json!([]);
                self.windows = json!([]);
                let metadata: Value = serde_json::from_str(include_str!(
                    "../../tests/fixtures/ghostty-remote.json"
                ))?;
                let mut replica = Replica::new(
                    metadata["nonce"]
                        .as_u64()
                        .context("preview nonce missing")?,
                );
                for chunk in
                    include_bytes!("../../tests/fixtures/ghostty-remote.bin").chunks(7)
                {
                    replica.feed(chunk)?;
                }
                self.screen = Some(Arc::new(replica.terminal()?.frame()?));
                self.replica = Some(replica);
                self.phase = "preview".into();
                self.error.clear();
                self.notice = "Offline preview · captured test terminal".into();
                self.controlled = false;
                self.publish();
            }
            other => anyhow::bail!("Unknown command: {other}"),
        }
        Ok(())
    }
    async fn open_block(&mut self, block: String) -> Result<()> {
        self.open_block_with_layout(block,false).await
    }
    async fn restore_minimized(&mut self, block: &str) -> Result<()> {
        if let Some((_, pane)) = minimized_pane(&self.windows, block) {
            let layer = pane["layerId"].as_str().context("Floating layer is unavailable")?.to_owned();
            let args = json!({"session_id":self.selected_session,"layer_id":layer,"state":"normal"});
            self.control.as_mut().context("not connected")?.call("session.set_layer_state",args).await?;
            // Rex acknowledged the whole layer. Start opening the stream now;
            // the normal post-open refresh reconciles any later desktop changes.
            for window in self.windows.as_array_mut().into_iter().flatten() {
                for pane in window["layout"].as_array_mut().into_iter().flatten() {
                    if pane["layerId"] == layer { pane["minimized"] = json!(false); }
                }
            }
        }
        Ok(())
    }
    async fn open_block_with_layout(&mut self, block: String, keep_tiled: bool) -> Result<()> {
        ensure!(!self.selected_session.is_empty(), "Choose a session first");
        let block = visible_target(&self.windows, block);
        if self.selected_block == block && self.data.is_some() && self.replica.is_some() {
            self.publish();
            return Ok(());
        }
        self.open_block_stream(block.clone(), keep_tiled).await?;
        self.refresh_view().await?;
        if self.controlled && self.selected_block == block {
            // Returning to an owned pane skips take_selected, so restore its keyboard focus here.
            self.focus_selected().await?;
        }
        Ok(())
    }

    async fn open_block_stream(&mut self, block: String, keep_tiled: bool) -> Result<()> {
        let minimized = minimized_pane(&self.windows, &self.selected_block).is_some();
        let window=pane_window(&self.windows,&self.selected_block);
        let floating=window.is_some_and(|w|pane_layout(w).any(|p|p["id"]==self.selected_block && p["floating"]==true));
        let same_tab = window.is_some_and(|w| w["blocks"].as_array().into_iter().flatten().any(|pane| pane["id"] == block));
        let keep_layout=keep_tiled && window.is_some_and(|w| {
            w["zoomed"].as_str().is_none_or(str::is_empty) &&
                pane_layout(w).any(|pane|pane["id"]==block && pane["minimized"]!=true)
        });
        let handoff = same_tab && self.mirror.wants(&self.selected_session, &block);
        let keep_claim = self.visible && !self.paused && !minimized && !block.is_empty();
        let retain_as_mirror = keep_claim && (keep_layout || handoff && !floating) &&
            self.data.is_some() && self.replica.is_some();
        let was_controlled = self.controlled;
        if keep_claim {
            if was_controlled && let Some(control) = self.control.as_mut() {
                // The phone keeps sizing the departing pane, but it no longer has keyboard focus.
                let _ = control.start_call("block.event", json!({
                    "session_id": self.selected_session, "block_id": self.selected_block,
                    "event": {"type":"focus","payload":{"action":"out"}}
                })).await;
            }
        } else {
            self.release_selected().await?;
        }
        if !same_tab && !block.is_empty() {
            self.close_retained_floating().await;
            if keep_claim {
                self.owned_blocks.extend(self.mirror.park());
                self.mirror_waiting.clear();
            } else {
                self.mirror.request("", Vec::new());
            }
        }
        let restored = if self.retained_floating.as_ref().is_some_and(|p| p.block == block) {
            self.retained_floating.take()
        } else if handoff {
            self.mirror.take(&block).await.map(|(data, replica)| RetainedFloating {
                block: block.clone(), data, replica,
            })
        } else {
            // Giving up on focus should leave the other visible panes streaming.
            if same_tab && !keep_layout && !block.is_empty() {
                self.mirror.request("", Vec::new());
                if let Some(control) = self.control.as_mut() {
                    self.mirror.start(control).await;
                }
            }
            None
        };
        if retain_as_mirror && let (Some(data), Some(replica)) = (self.data.take(), self.replica.take()) {
            // The departing pane stays visible even when the incoming pane was the only mirror.
            self.mirror.adopt(&self.selected_session,self.selected_block.clone(), data, replica, was_controlled);
        } else if floating && same_tab && !minimized && let (Some(data),Some(replica))=(self.data.take(),self.replica.take()) {
            self.close_retained_floating().await;
            self.retained_floating=Some(RetainedFloating {block:self.selected_block.clone(),data,replica});
        } else if let Some(mut data)=self.data.take() {
            let _=tokio::time::timeout(Duration::from_secs(2),data.close()).await;
        }
        self.data_resume = None;
        self.selected_block = block;
        self.controlled = false;
        self.screen = None;
        if self.selected_block.is_empty() {
            self.replica = None;
            self.notice = "Choose a terminal".into();
            return Ok(());
        }
        if let Some(pane)=restored {
            self.replica=Some(pane.replica);self.data=Some(pane.data);
            self.dirty=true;
        } else {
            let data = Data::open(
                self.endpoint.as_ref().context("no server")?,
                self.control.as_mut().context("not connected")?,
                &self.selected_session,
                &self.selected_block,
                Trace::default(),
            ).await?;
            self.replica = Some(Replica::new(data.nonce));
            self.data = Some(data);
        }
        self.awaiting_pane_selection = false;
        self.error.clear();
        self.notice = "Viewing terminal; take control to type".into();
        Ok(())
    }
    async fn refresh_close_view(&mut self) -> Result<bool> {
        if self.selected_session.is_empty() {return Ok(false);}
        match self.refresh_view().await {
            Ok(())=>Ok(true),
            Err(error) if missing_terminal(&error) => {
                self.clear_ended_session(self.selected_session.clone());
                self.refresh_session_catalog().await?;
                Ok(false)
            }
            Err(error)=>Err(error)
        }
    }
    async fn take_selected(&mut self) -> Result<()> {
        ensure!(!self.selected_block.is_empty(),"Choose a terminal first");
        self.control.as_mut().context("not connected")?.call("session.own_block",json!({"session_id":self.selected_session,"block_ids":[self.selected_block],"mode":"take"})).await?;
        self.refresh_view().await?;
        self.focus_selected().await
    }
    async fn focus_selected(&mut self) -> Result<()> {
        if self.selected_block.is_empty() { return Ok(()); }
        self.apply_theme().await?;
        // A focus notification makes programs re-query even if set_theme was
        // deduplicated because the canonical terminal already had these colors.
        self.control.as_mut().context("not connected")?.call("block.event",json!({"session_id":self.selected_session,"block_id":self.selected_block,
            "event":{"type":"focus","payload":{"action":"in"}}})).await?;
        self.notice="You control this terminal".into();self.publish();Ok(())
    }
    async fn apply_theme(&mut self) -> Result<()> {
        // Viewing must not recolor an unowned terminal on another device.
        // Apply our appearance only while we own the pane; take_selected refreshes ownership first.
        if self.controlled && !self.paused
            && !self.selected_session.is_empty()
            && !self.selected_block.is_empty()
            && let (Some(control), Some(theme)) = (&mut self.control, &self.theme)
        {
            control
                .terminal(
                    &self.selected_session,
                    &self.selected_block,
                    "set_theme",
                    theme.clone(),
                )
                .await?;
        }
        Ok(())
    }
    async fn release_selected(&mut self) -> Result<()> {
        if !self.selected_session.is_empty()
            && !self.selected_block.is_empty()
            && let Some(control) = self.control.as_mut()
        {
            control
                .call(
                    "session.disown_block",
                    json!({"session_id":self.selected_session,"block_ids":[self.selected_block]}),
                )
                .await?;
        }
        self.owned_blocks.remove(&self.selected_block);
        self.controlled = false;
        Ok(())
    }

    async fn release_session_panes(&mut self) -> Result<()> {
        self.owned_blocks.extend(self.mirror.park());
        self.mirror_waiting.clear();
        if self.controlled && !self.selected_block.is_empty() {
            self.owned_blocks.insert(self.selected_block.clone());
        }
        if let Some(control) = self.control.as_mut() {
            self.mirror.start(control).await;
            if !self.selected_session.is_empty() && !self.owned_blocks.is_empty() {
                let request = json!({"session_id":self.selected_session,"block_ids":self.owned_blocks});
                if let Err(error) = control.call("session.disown_block", request).await {
                    if !missing_terminal(&error) { return Err(error); }
                    // A pane closed after the last view may invalidate the batch.
                    match control.call("session.view", json!({"session_id":self.selected_session})).await {
                        Ok(view) => self.set_view(&view),
                        Err(error) if missing_terminal(&error) => self.owned_blocks.clear(),
                        Err(error) => return Err(error),
                    }
                    if !self.owned_blocks.is_empty() {
                        self.control.as_mut().context("not connected")?.call("session.disown_block",
                            json!({"session_id":self.selected_session,"block_ids":self.owned_blocks})).await?;
                    }
                }
            }
        }
        self.owned_blocks.clear();
        self.controlled = false;
        Ok(())
    }
}
fn preview_task(endpoint: Endpoint, session: String, ids: Vec<String>) -> PreviewTask {
    Box::pin(async move {
        tokio::time::timeout(Duration::from_secs(5), async {
            let mut control = Control::connect(endpoint.clone(), None, Trace::default()).await?;
            control
                .call("client.set_label", json!({"label":"rex-android-preview"}))
                .await?;
            control.attach(&session).await?;
            let mut frames = Vec::new();
            for id in ids {
                let result = async {
                    let mut data =
                        Data::open(&endpoint, &mut control, &session, &id, Trace::default())
                            .await?;
                    let mut replica = Replica::new(data.nonce);
                    // One-shot previews need late Kitty pixels too. Only the
                    // selected live terminal presents before FINISH.
                    while !replica.snapshot_complete() {
                        replica.feed(&data.read().await?)?;
                    }
                    let frame = Arc::new(replica.terminal()?.frame()?);
                    let _ = data.close().await;
                    Ok::<_, anyhow::Error>(frame)
                }
                .await;
                if let Ok(frame) = result {
                    frames.push((id, frame));
                }
            }
            let _ = control.close().await;
            Ok::<_, anyhow::Error>(frames)
        })
        .await
        .context("pane preview timed out")?
    })
}
// Executable identifiers and a display directory cross JNI; never command arguments or user IDs.
fn program_candidates(process: &Value, status: &Value, terminal: &Value) -> Value {
    let foreground = &process["foreground"];
    let mut names: Vec<String> = Vec::new();
    let mut add = |value: &Value| {
        if let Some(name) = value.as_str().filter(|s| !s.is_empty()) {
            let name: String = name.chars().take(512).collect();
            if !names.contains(&name) {
                names.push(name);
            }
        }
    };
    if foreground["pid"].is_number() && foreground["name"].is_string() {
        for record in status["records"].as_array().into_iter().flatten() {
            // Never label a new foreground program using an old status record.
            if record["owner"]["pid"] == foreground["pid"]
                && record["owner"]["name"] == foreground["name"]
            {
                add(&record["effective_app"]);
                add(&record["app"]);
            }
        }
    } else if foreground.is_null()
        && terminal["owner"].is_null()
        && terminal["title"].as_str().is_some_and(|title| title.starts_with("π - "))
        && status["records"].as_array().into_iter().flatten().any(|record|
            record["owner"].is_null()
                && record["id"].as_str().is_none_or(str::is_empty)
                && record["app"] == "pi")
    {
        // Some Rex hosts report only the login wrapper. Pi's own title corroborates
        // its ownerless status without taking precedence over an identified process.
        add(&json!("pi"));
    }
    let source = if foreground.is_object() {
        foreground
    } else {
        &process["child"]
    };
    for key in ["script", "argv0", "invoked_path", "path", "name"] {
        add(&source[key]);
    }
    json!(names)
}
fn missing_terminal(error: &anyhow::Error) -> bool {
    error.downcast_ref::<rex_core::control::RpcError>().is_some_and(|e|matches!(e.code.as_str(),"not_found"|"session_not_found"))
}
fn clear_ownership_error(error: &mut String) {
    if matches!(error.as_str(),"Take control before typing"|"Take control before typing or resizing") {error.clear();}
}
fn same_process(a: &Value,b: &Value) -> bool {a["pid"].is_number() && a["name"].is_string() && a["pid"]==b["pid"] && a["name"]==b["name"]}
fn display_title(event: &Value) -> String {event["title"].as_str().unwrap_or("").chars().filter(|c|!c.is_control()).take(256).collect()}
fn program_working(process: &Value, status: &Value) -> bool {
    let foreground=&process["foreground"];
    foreground["pid"].is_number() && foreground["name"].is_string() &&
        status["records"].as_array().into_iter().flatten().any(|record|
            record["owner"]["pid"]==foreground["pid"] && record["owner"]["name"]==foreground["name"] && record["state"]=="working")
}
pub(crate) fn program_title(process: &Value, status: &Value, terminal: &Value) -> String {
    let foreground=&process["foreground"];
    let owned=|record: &Value| foreground["pid"].is_number() && foreground["name"].is_string()
        && record["owner"]["pid"]==foreground["pid"] && record["owner"]["name"]==foreground["name"];
    let title=owned(terminal).then(|| terminal["title"].as_str().filter(|t| !t.trim().is_empty())).flatten()
        .or_else(|| status["records"].as_array().into_iter().flatten()
            .filter(|r| r["id"].as_str().is_none_or(str::is_empty) && owned(r))
            .find_map(|r| r["title"].as_str().filter(|t| !t.trim().is_empty()))).unwrap_or("");
    title.chars().filter(|c| !c.is_control()).take(256).collect()
}
fn program_directory(process: &Value) -> String {
    let source = if process["foreground"].is_object() {
        &process["foreground"]
    } else {
        &process["child"]
    };
    let cwd = source["cwd"].as_str().unwrap_or("");
    let home = process["home"].as_str().unwrap_or("").trim_end_matches('/');
    let display = if !home.is_empty() && cwd == home {
        "~".to_owned()
    } else if !home.is_empty()
        && let Some(relative) = cwd.strip_prefix(&format!("{home}/"))
    {
        format!("~/{relative}")
    } else {
        cwd.to_owned()
    };
    display
        .chars()
        .filter(|c| !c.is_control())
        .take(1024)
        .collect()
}
fn program_task(endpoint: Endpoint, session: String, ids: Vec<String>) -> ProgramTask {
    Box::pin(async move {
        tokio::time::timeout(Duration::from_secs(8), async {
            let mut control = Control::connect(endpoint, None, Trace::default()).await?;
            control
                .call("client.set_label", json!({"label":"rex-android-programs"}))
                .await?;
            control.attach(&session).await?;
            let mut programs = serde_json::Map::new();
            let mut directories = serde_json::Map::new();
            let mut titles = serde_json::Map::new();
            let mut working = Vec::new();
            let mut owners = serde_json::Map::new();
            for id in ids.into_iter().take(64) {
                if let Ok(process) = control.terminal(&session, &id, "process", json!({})).await {
                    let status = control
                        .terminal(&session, &id, "program_status", json!({}))
                        .await
                        .unwrap_or(Value::Null);
                    let terminal=control.terminal(&session,&id,"title",json!({})).await.unwrap_or(Value::Null);
                    owners.insert(id.clone(),json!({"pid":process["foreground"]["pid"],"name":process["foreground"]["name"]}));
                    if program_working(&process,&status) {working.push(id.clone());}
                    titles.insert(id.clone(),json!(program_title(&process,&status,&terminal)));
                    directories.insert(id.clone(), json!(program_directory(&process)));
                    programs.insert(id, program_candidates(&process, &status, &terminal));
                }
            }
            let _ = control.close().await;
            Ok::<_, anyhow::Error>(json!({"programs":programs,"directories":directories,"titles":titles,"working":working,"owners":owners}))
        })
        .await
        .context("program metadata timed out")?
    })
}
fn summary_task(endpoint: Endpoint, sessions: Value, own: String) -> SummaryTask {
    Box::pin(async move {
        tokio::time::timeout(Duration::from_secs(8), async {
            let mut control = Control::connect(endpoint, None, Trace::default()).await?;
            control
                .call("client.set_label", json!({"label":"rex-android-summary"}))
                .await?;
            let clients = control
                .call("client.list", json!({"kinds":["app"]}))
                .await?["clients"]
                .clone();
            let mut details = serde_json::Map::new();
            for session in sessions.as_array().into_iter().flatten().take(64) {
                let Some(id) = session["session_id"].as_str() else {
                    continue;
                };
                let mut attachments = Vec::new();
                let mut local = false;
                let mut lost = false;
                for client in clients.as_array().into_iter().flatten() {
                    if client["info"]["label"].as_str().is_some_and(|s| {
                        s.starts_with("rex-android-rename") || s.starts_with("rex-android-close")
                            || s.starts_with("rex-android-preview")
                            || s.starts_with("rex-android-summary")
                            || s.starts_with("rex-android-programs")
                            || s.starts_with("rex-android-activity")
                            || s.starts_with("rex-android-pane-catalog")
                    }) {
                        continue;
                    }
                    if !client["session_ids"]
                        .as_array()
                        .into_iter()
                        .flatten()
                        .any(|s| s.as_str() == Some(id))
                    {
                        continue;
                    }
                    if client["connection_state"] == "lost" {
                        lost = true;
                        continue;
                    }
                    if client["client_id"].as_str() == Some(own.as_str()) {
                        local = true;
                    } else {
                        let device = client["principal"]["node"]
                            .as_str()
                            .filter(|s| !s.is_empty())
                            .or(client["info"]["platform"].as_str())
                            .unwrap_or("another device");
                        if !attachments.iter().any(|s| s == device) {
                            attachments.push(device.to_owned());
                        }
                    }
                }
                let attachment = if local {
                    "this device".to_owned()
                } else if !attachments.is_empty() {
                    format!("attached on {}", attachments.join(", "))
                } else if lost {
                    "reconnecting".to_owned()
                } else {
                    "detached".to_owned()
                };
                if let Ok(attached) = control.attach(id).await {
                    let windows = &attached["view"]["windows"];
                    let tabs = windows.as_array().map_or(0, Vec::len);
                    let panes = windows
                        .as_array()
                        .into_iter()
                        .flatten()
                        .flat_map(|w| w["layers"].as_array().into_iter().flatten())
                        .flat_map(|l| l["blocks"].as_array().into_iter().flatten())
                        .filter(|b| b["creator_name"] == "com.superlogical.terminal")
                        .count();
                    details.insert(
                        id.to_owned(),
                        json!({"attachment":attachment,"tabs":tabs,"panes":panes}),
                    );
                    let _ = control
                        .call("session.detach", json!({"session_id":id}))
                        .await;
                }
            }
            let _ = control.close().await;
            Ok::<_, anyhow::Error>(Value::Object(details))
        })
        .await
        .context("session details timed out")?
    })
}

fn pane_catalog_task(endpoint: Endpoint) -> SummaryTask {
    Box::pin(async move {
        use tokio::time::{Instant, timeout, timeout_at};

        let (mut control, sessions) = timeout(Duration::from_secs(5), async {
            let mut control = Control::connect(endpoint, None, Trace::default()).await?;
            control.call("client.set_label", json!({"label":"rex-android-pane-catalog"})).await?;
            // A passive attachment can read topology without opening streams or taking control.
            let sessions = control.call("session.attach_all", json!({"event_scope":"session"})).await?["sessions"].clone();
            Ok::<_, anyhow::Error>((control, sessions))
        }).await??;

        let mut items = Vec::new();
        let mut partial = false;
        let mut timed_out = false;
        let topology_deadline = Instant::now() + Duration::from_secs(7);
        for session in sessions.as_array().into_iter().flatten() {
            let Some(id) = session["session_id"].as_str() else { continue };
            match timeout_at(topology_deadline, control.call("session.view", json!({"session_id":id}))).await {
                Ok(Ok(view)) => {
                    for window in view["windows"].as_array().into_iter().flatten() {
                        for block in window["layers"].as_array().into_iter().flatten()
                            .flat_map(|layer| layer["blocks"].as_array().into_iter().flatten())
                        {
                            if block["creator_name"] != "com.superlogical.terminal" { continue; }
                            if let Some(block_id) = block["block_id"].as_str() {
                                items.push(json!({"session":id,"block":block_id,"label":block["label"]}));
                            }
                        }
                    }
                }
                Ok(Err(_)) => partial = true,
                Err(_) => {
                    partial = true;
                    timed_out = true;
                    break;
                }
            }
        }
        if !timed_out {
            let metadata_deadline = Instant::now() + Duration::from_secs(6);
            for item in &mut items {
                let session = item["session"].as_str().unwrap_or_default();
                let block = item["block"].as_str().unwrap_or_default();
                let result = timeout_at(metadata_deadline, async {
                    let process = control.terminal(session, block, "process", json!({})).await?;
                    let title = control.terminal(session, block, "title", json!({})).await.unwrap_or(Value::Null);
                    Ok::<_, anyhow::Error>((process, title))
                }).await;
                match result {
                    Ok(Ok((process, title))) => {
                        item["programs"] = program_candidates(&process, &Value::Null, &Value::Null);
                        item["directory"] = json!(program_directory(&process));
                        item["title"] = json!(program_title(&process, &Value::Null, &title));
                    }
                    Ok(Err(error)) if missing_terminal(&error) => (),
                    Ok(Err(_)) => partial = true,
                    Err(_) => {
                        partial = true;
                        break;
                    }
                }
            }
        }
        let _ = timeout(Duration::from_secs(1), control.close()).await;
        Ok(json!({"items":items,"partial":partial}))
    })
}

fn rgb(rgb: [u8; 3]) -> u32 {
    ((rgb[0] as u32) << 16) | ((rgb[1] as u32) << 8) | rgb[2] as u32
}
fn frame_value(frame: &Frame) -> Value {
    let cells: Vec<Value> = frame
        .cells
        .iter()
        .map(|c| {
            json!([
                c.x,
                c.y,
                c.text,
                rgb(c.foreground),
                rgb(c.background),
                c.attributes,
                c.width,
                c.hyperlink
            ])
        })
        .collect();
    json!({"columns":frame.columns,"rows":frame.rows,"scrollbar":[frame.scroll_total,frame.scroll_offset,frame.scroll_visible],"input_flags":frame.input_flags,"cursor":frame.cursor,"cursor_position":frame.cursor_position,"foreground":rgb(frame.foreground),"background":rgb(frame.background),"cells":cells})
}

#[cfg(test)]
mod split_tests {
    use super::*;
    #[test]
    fn splits_name_one_block_per_side_and_skip_non_terminals() {
        let layout=json!({"rect":[0,0,1,1],"split":{"split_id":"s1","direction":"horizontal","ratio":0.5,
            "before":{"rect":[0,0,0.5,1],"block_id":"a"},
            "after":{"rect":[0.5,0,0.5,1],"split":{"split_id":"s2","direction":"vertical","ratio":0.3,
                "before":{"rect":[0.5,0,0.5,0.3],"block_id":"web"},
                "after":{"rect":[0.5,0.3,0.5,0.7],"block_id":"c"}}}}});
        let ids:BTreeSet<&str>=["a","c"].into_iter().collect();
        let mut out=Vec::new();
        collect_splits(&layout,&ids,0,&mut out);
        // s2's before side holds only a non-terminal block, so it cannot be named and is skipped.
        assert_eq!(out,vec![json!({"id":"s1","direction":"horizontal","ratio":0.5,"rect":[0,0,1,1],"before":"a","after":"c"})]);
    }
}
#[cfg(test)]
mod tests {
    fn navigation_actor()->Actor {
        let shared=Arc::new(Mutex::new(Shared {revision:0,json:String::new(),activity:Arc::default(),pending_previews:BTreeMap::new(),frame:None,key:String::new(),wire:crate::updates::WireBase::default()}));
        Actor::new(shared,Arc::new(crate::updates::Updates::default()))
    }
    #[test] fn navigation_intent_is_published_only_for_accepted_navigation() {
        let (tx,mut rx)=mpsc::channel(1);
        let actor=navigation_actor();
        let session=Session {tx,latest_navigation:actor.latest_navigation.clone(),shared:actor.shared.clone(),updates:actor.updates.clone()};
        session.command(json!({"type":"block","navigationId":2})).unwrap();
        assert_eq!(session.latest_navigation.load(Ordering::Acquire),2);
        assert!(session.command(json!({"type":"session","navigationId":9})).is_err());
        assert_eq!(session.latest_navigation.load(Ordering::Acquire),2,"a full queue must not publish a rejected intent");
        rx.try_recv().unwrap();
        session.command(json!({"type":"resize","navigationId":99})).unwrap();rx.try_recv().unwrap();
        assert_eq!(session.latest_navigation.load(Ordering::Acquire),2,"resize is not a new user selection");
        session.command(json!({"type":"session","navigationId":1})).unwrap();
        assert_eq!(session.latest_navigation.load(Ordering::Acquire),2,"out-of-order admission cannot lower the latest selection");
    }
    #[tokio::test] async fn superseded_navigation_and_control_work_never_start() {
        let mut actor=navigation_actor();
        actor.latest_navigation.store(3,Ordering::Release);
        actor.selected_session="s".into();actor.selected_block="original".into();
        for kind in ["block","session","take","resize"] {
            for id in [1,2] {
                actor.execute_command(json!({"type":kind,"id":"obsolete","navigationId":id})).await;
                assert_eq!(actor.navigation_id,0);assert_eq!(actor.selected_block,"original");assert!(actor.error.is_empty());
            }
        }
        actor.execute_command(json!({"type":"block","id":"latest","navigationId":3})).await;
        assert_eq!(actor.navigation_id,3);assert_eq!(actor.selected_block,"latest");
        assert!(!actor.error.is_empty(),"the latest request actually executes, even without a test server");
        actor.execute_command(json!({"type":"move_tab","requestId":44,"navigationId":1})).await;
        assert_eq!(actor.tab_move_id,44,"mutations must retain their outcome acknowledgements");
    }
    #[test] fn an_old_navigation_failure_cannot_acknowledge_or_erase_new_intent() {
        let mut actor=navigation_actor();actor.latest_navigation.store(3,Ordering::Release);
        actor.navigation_id=2;actor.error="current error".into();
        actor.command_error(anyhow::anyhow!("old pane vanished"),Some(1),false);
        assert_eq!(actor.navigation_id,2);assert_eq!(actor.error,"current error");
        actor.command_error(anyhow::anyhow!("latest pane vanished"),Some(3),false);
        assert_eq!(actor.navigation_id,3);assert_eq!(actor.error,"latest pane vanished");
    }

    use super::*;
    #[test]
    fn terminal_edits_validate_targets_names_and_layout_before_sending() {
        let windows=json!([{"id":"w","blocks":[{"id":"a"},{"id":"b"},{"id":"f"}],"layout":[{"id":"a","floating":false},{"id":"b","floating":false},{"id":"f","floating":true}]}]);
        let command=|kind:&str,id:&str|json!({"type":kind,"session":"s","window":"w","id":id,"label":"  Logs 🦀  ","anchor":"a","direction":"below"});
        let (method,args)=terminal_edit_call(&windows,"s",&command("rename_tab","w")).unwrap();assert_eq!(method,"session.set_window_label");assert_eq!(args["label"],"Logs 🦀");
        assert_eq!(terminal_edit_call(&windows,"s",&command("rename_pane","b")).unwrap().0,"session.set_block_label");
        assert_eq!(terminal_edit_call(&windows,"s",&command("float_pane","b")).unwrap().0,"session.float_block");
        let (_,args)=terminal_edit_call(&windows,"s",&command("dock_pane","f")).unwrap();assert_eq!(args["anchor_block_id"],"a");assert_eq!(args["direction"],"vertical");
        for (kind,id) in [("rename_tab","b"),("rename_pane","missing"),("float_pane","f"),("dock_pane","a")] {assert!(terminal_edit_call(&windows,"s",&command(kind,id)).is_err());}
        assert!(terminal_edit_call(&windows,"stale",&command("rename_pane","b")).is_err());
        let mut invalid=command("rename_pane","a");invalid["label"]=json!("bad\nname");assert!(terminal_edit_call(&windows,"s",&invalid).is_err());
        invalid["label"]=json!("x".repeat(129));assert!(terminal_edit_call(&windows,"s",&invalid).is_err());
        invalid["label"]=json!("");assert_eq!(terminal_edit_call(&windows,"s",&invalid).unwrap().1["label"],"");
        let mut invalid=command("dock_pane","f");invalid["anchor"]=json!("f");assert!(terminal_edit_call(&windows,"s",&invalid).is_err());
        let only=json!([{"id":"w","blocks":[{"id":"a"}],"layout":[{"id":"a","floating":false}]}]);assert!(terminal_edit_call(&only,"s",&command("float_pane","a")).is_err());
        let place=json!({"type":"place_pane","session":"s","window":"w","id":"b","anchor":"a","position":"below","tiled":["a","b"]});
        for (key,value) in [("anchor",json!("f")),("anchor",json!("b")),("tiled",json!(["b","a"])),("position",json!("center"))] {
            let mut invalid=place.clone();invalid[key]=value;
            assert!(terminal_edit_call(&windows,"s",&invalid).is_err());
        }
    }

    #[test]
    fn pane_reorder_plan_moves_both_ways_and_rejects_stale_or_invalid_targets() {
        let windows=json!([{"id":"w","blocks":[{"id":"a"},{"id":"b"},{"id":"c"},{"id":"d"}]}]);
        for (id,before,wanted) in [("a","d",vec!["b","c","a","d"]),("d","a",vec!["d","a","b","c"]),("b","",vec!["a","c","d","b"])] {
            let command=json!({"window":"w","blocks":["a","b","c","d"],"id":id,"before":before});
            let swaps=pane_reorder_swaps(&windows,&command).unwrap();let mut actual=vec!["a","b","c","d"];
            for(a,b) in swaps {let i=actual.iter().position(|id|*id==a).unwrap();let j=actual.iter().position(|id|*id==b).unwrap();actual.swap(i,j);}
            assert_eq!(actual,wanted);
        }
        let valid=json!({"window":"w","blocks":["a","b","c","d"],"id":"a","before":"b"});
        assert!(pane_reorder_swaps(&windows,&valid).unwrap().is_empty());
        for (key,value) in [("window",json!("gone")),("blocks",json!(["a","b"])),("id",json!("gone")),("before",json!("gone")),("before",json!("a"))] {
            let mut invalid=valid.clone();invalid[key]=value;assert!(pane_reorder_swaps(&windows,&invalid).is_err());
        }
    }
    #[test]
    fn pane_layout_preserves_nested_window_rects_and_floating_layers() {
        let shared=Arc::new(Mutex::new(Shared{revision:0,json:String::new(),pending_previews:BTreeMap::new(),frame:None,key:String::new(),wire:crate::updates::WireBase::default(),activity:Arc::default()}));
        let mut actor=Actor::new(shared,Arc::new(crate::updates::Updates::default()));
        let blocks=|ids:&[&str]|ids.iter().map(|id|json!({"block_id":id,"creator_name":"com.superlogical.terminal"})).collect::<Vec<_>>();
        actor.set_view(&json!({"windows":[{"window_id":"w","layers":[
            {"kind":"tiled","blocks":blocks(&["a","b","c"]),"layout":{"rect":[0,0,1,1],"split":{
                "before":{"block_id":"a","rect":[0,0,0.35,1]},
                "after":{"rect":[0.35,0,0.65,1],"split":{
                    "before":{"block_id":"b","rect":[0.35,0,0.65,0.4]},
                    "after":{"block_id":"c","rect":[0.35,0.4,0.65,0.6]}}}}}},
            {"kind":"floating","bounds":[0.1,0.1,0.5,0.5],"blocks":blocks(&["d"]),"layout":{"block_id":"d","rect":[0.1,0.1,0.5,0.5]}}
        ]}]}));
        let layout=actor.windows[0]["layout"].as_array().unwrap();
        assert_eq!(layout.len(),4);
        assert_eq!(layout[0],json!({"id":"a","rect":[0,0,0.35,1],"layer":0,"floating":false}));
        assert_eq!(layout[2]["rect"],json!([0.35,0.4,0.65,0.6]));
        assert_eq!(layout[3],json!({"id":"d","rect":[0.1,0.1,0.5,0.5],"layer":1,"floating":true}));
    }
    #[test]
    fn pane_layout_ignores_unknown_ids_and_invalid_leaves() {
        let ids=BTreeSet::from(["a"]);let mut out=Vec::new();
        for node in [Value::Null,json!({}),json!({"block_id":"other","rect":[0,0,1,1]}),
            json!({"block_id":"a","rect":[0,0,0,1]}),json!({"block_id":"a","rect":[0,0,1]}),
            json!({"block_id":"a","rect":[0,"bad",1,1]})] {
            collect_pane_layout(&node,&ids,0,false,0,&mut out);
        }
        assert!(out.is_empty());
    }
    #[test]
    fn layout_only_changes_keep_the_selected_pane_and_program_metadata() {
        let shared=Arc::new(Mutex::new(Shared{revision:0,json:String::new(),pending_previews:BTreeMap::new(),frame:None,key:String::new(),wire:crate::updates::WireBase::default(),activity:Arc::default()}));
        let mut actor=Actor::new(shared,Arc::new(crate::updates::Updates::default()));
        let view=|rect:Value|json!({"windows":[{"window_id":"w","layers":[{"kind":"tiled",
            "blocks":[{"block_id":"a","creator_name":"com.superlogical.terminal"}],"layout":{"block_id":"a","rect":rect}}]}]});
        actor.set_view(&view(json!([0,0,1,1])));
        actor.selected_block="a".into();actor.programs=json!({"a":["codex"]});actor.titles=json!({"a":"Task"});actor.working=json!(["a"]);
        actor.set_view(&view(json!([0,0,0.5,1])));
        assert_eq!(actor.windows[0]["layout"][0]["rect"],json!([0,0,0.5,1]));
        assert_eq!(actor.selected_block,"a");assert_eq!(actor.programs,json!({"a":["codex"]}));
        assert_eq!(actor.titles["a"],"Task");assert_eq!(actor.working,json!(["a"]));
    }
    #[test]
    fn split_arguments_keep_the_anchor_side_shell_and_desktop_focus_for_both_directions() {
        for direction in ["horizontal","vertical"] {
            let args=new_split_arguments("session:one","block:current",&json!({"direction":direction})).unwrap();
            assert_eq!(args,json!({"session_id":"session:one","anchor_id":"block:current","direction":direction,"side":"after",
                "layout":{"block":{"flavor":"com.superlogical.terminal.shell"}},"focus":false}));
        }
        assert_eq!(new_split_arguments("s","b",&json!({})).unwrap()["direction"],"horizontal");
        for invalid in [json!("below"),json!(""),json!(null),json!(false),json!(1)] {
            assert!(new_split_arguments("s","b",&json!({"direction":invalid})).is_err());
        }
    }
    #[test]
    fn split_rejects_stale_targets_and_invalid_direction_before_starting_creation() {
        let shared=Arc::new(Mutex::new(Shared{revision:0,json:String::new(),pending_previews:BTreeMap::new(),frame:None,key:String::new(),wire:crate::updates::WireBase::default(),activity:Arc::default()}));
        let mut actor=Actor::new(shared,Arc::new(crate::updates::Updates::default()));
        actor.phase="connected".into();actor.selected_session="s".into();actor.selected_block="b".into();
        actor.windows=json!([{"id":"w","blocks":[{"id":"b"}]}]);
        let runtime=tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        for command in [json!({"type":"new_pane","session":"old","anchor":"b","direction":"vertical"}),
            json!({"type":"new_pane","session":"s","anchor":"old","direction":"vertical"}),
            json!({"type":"new_pane","session":"s","anchor":"b","direction":"invalid"})] {
            assert!(runtime.block_on(actor.command(command)).is_err());
            assert!(!actor.creating);assert!(actor.control.is_none());
        }
    }
    #[test]
    fn split_can_target_an_inactive_pane_without_navigating_to_it() {
        let windows=json!([{"id":"w","blocks":[{"id":"one"},{"id":"two"},{"id":"three"}]}]);
        let command=json!({"type":"new_pane","session":"s","anchor":"three","direction":"vertical"});
        let anchor=split_anchor(&windows,&command).unwrap();
        assert_eq!(anchor,"three");
        assert_eq!(new_split_arguments("s",anchor,&command).unwrap()["anchor_id"],"three");
        for invalid in [json!({"anchor":"gone"}),json!({"anchor":""}),json!({}),json!({"anchor":1})] {
            assert!(split_anchor(&windows,&invalid).is_err());
        }
        assert!(split_anchor(&json!([]),&command).is_err());
    }
    #[test]
    fn header_directory_abbreviates_only_the_real_home_and_prefers_foreground() {
        assert_eq!(
            program_directory(
                &json!({"home":"/home/al","foreground":{"cwd":"/home/al/code"},"child":{"cwd":"/old"}})
            ),
            "~/code"
        );
        assert_eq!(
            program_directory(&json!({"home":"/home/al/","foreground":{"cwd":"/home/al"}})),
            "~"
        );
        assert_eq!(
            program_directory(&json!({"home":"/home/al","foreground":{"cwd":"/home/alice/code"}})),
            "/home/alice/code"
        );
        assert_eq!(program_directory(&json!({"child":{"cwd":"/tmp"}})), "/tmp");
        assert_eq!(
            program_directory(&json!({"foreground":{"name":"private"},"child":{"cwd":"/old"}})),
            ""
        );
    }
    #[test]
    fn program_candidates_ignore_stale_status_owners_and_private_process_fields() {
        let process = json!({"foreground":{"pid":12,"name":"node","script":"/tools/agent.js","argv0":"node","cwd":"/private/work","argv":["secret"]}});
        let status = json!({"records":[
            {"app":"wrong-pid","owner":{"pid":11,"name":"node"}},
            {"app":"reused-pid","owner":{"pid":12,"name":"python"}},
            {"effective_app":"claude-code","owner":{"pid":12,"name":"node"}}
        ]});
        assert_eq!(
            program_candidates(&process, &status, &Value::Null),
            json!(["claude-code", "/tools/agent.js", "node"])
        );
        assert_eq!(
            program_candidates(
                &json!({"foreground":null,"child":{"argv0":"-fish","name":"fish"}}),
                &status,
                &Value::Null
            ),
            json!(["-fish", "fish"])
        );
        assert_eq!(program_candidates(&json!({}), &status, &Value::Null), json!([]));
    }
    #[test]
    fn pi_status_and_title_identify_the_pane_when_foreground_metadata_is_missing() {
        let process = json!({"child":{"name":"login","path":"/usr/bin/login"}});
        let title = json!({"title":"π - rexling"});
        let status = json!({"records":[{"app":"pi","effective_app":"pi","state":"idle"}]});
        let login = json!(["/usr/bin/login", "login"]);
        assert_eq!(program_candidates(&process, &status, &title), json!(["pi", "/usr/bin/login", "login"]));
        assert_eq!(program_candidates(&process, &Value::Null, &title), login);
        assert_eq!(program_candidates(&process, &status, &json!({"title":"fish"})), login);
        let stale = json!({"records":[{"app":"pi","owner":{"pid":12,"name":"node"}}]});
        assert_eq!(program_candidates(&process, &stale, &title), login);
        let foreground = json!({"foreground":{"pid":13,"name":"nvim"}});
        assert_eq!(program_candidates(&foreground, &status, &title), json!(["nvim"]));
    }
    #[test]
    fn program_titles_prefer_live_terminal_titles_and_reject_stale_owners() {
        let process=json!({"foreground":{"pid":12,"name":"codex"}});
        let terminal=json!({"owner":{"pid":12,"name":"codex"},"title":"Terminal task"});
        let status=json!({"records":[
            {"owner":{"pid":11,"name":"codex"},"title":"Old task"},
            {"owner":{"pid":12,"name":"codex"},"id":"worker","title":"Child task"},
            {"owner":{"pid":12,"name":"codex"},"title":"Investigate Rex protocol"}]});
        assert_eq!(program_title(&process,&status,&terminal),"Terminal task");
        assert_eq!(program_title(&process,&status,&json!({})),"Investigate Rex protocol");
        assert_eq!(program_title(&process,&json!({}),&terminal),"Terminal task");
        assert_eq!(program_title(&process,&json!({}),&json!({"owner":{"pid":12,"name":"fish"},"title":"Old shell"})),"");
        assert_eq!(program_title(&json!({}),&status,&terminal),"");
    }
    #[test]
    fn streamed_titles_survive_stale_polling_and_reject_previous_processes() {
        let session=Session::spawn().unwrap();
        let mut actor=Actor::new(session.shared.clone(),session.updates.clone());
        actor.windows=json!([{"blocks":[{"id":"a"}]}]);
        let owner=json!({"pid":12,"name":"codex"});
        let details=json!({"programs":{},"directories":{},"titles":{"a":"Static task"},"working":[],"owners":{"a":owner}});
        actor.apply_program_details(details.clone());
        actor.program_event("a","title_changed",&json!({"owner":owner,"title":"⠋ Task"}));
        assert_eq!(actor.titles["a"],"⠋ Task");
        actor.apply_program_details(details.clone());
        assert_eq!(actor.titles["a"],"⠋ Task");
        actor.program_event("a","title_changed",&json!({"owner":{"pid":11,"name":"codex"},"title":"Old task"}));
        actor.apply_program_details(details.clone());
        assert_eq!(actor.titles["a"],"⠋ Task");
        actor.program_event("a","title_changed",&json!({"owner":owner,"title":""}));
        actor.apply_program_details(details.clone());assert_eq!(actor.titles["a"],"");
        actor.program_event("a","process_changed",&json!({"foreground":{"pid":13,"name":"fish"}}));
        actor.apply_program_details(details);assert_eq!(actor.titles["a"],"");
    }
    #[test]
    fn working_indicator_requires_a_live_foreground_owner_and_working_report() {
        let process=json!({"foreground":{"pid":12,"name":"codex"}});
        let status=|pid,name,state| json!({"records":[{"owner":{"pid":pid,"name":name},"state":state}]});
        assert!(program_working(&process,&status(12,"codex","working")));
        for state in ["idle","blocked","done","error","unknown"] {assert!(!program_working(&process,&status(12,"codex",state)));}
        assert!(!program_working(&process,&status(11,"codex","working")));
        assert!(!program_working(&process,&status(12,"fish","working")));
        assert!(!program_working(&json!({}),&status(12,"codex","working")));
    }
    #[test]
    fn offline_preview_runs_on_the_owner_thread_and_polls_by_revision() {
        let session = Session::spawn().unwrap();
        session.command(json!({"type":"preview"})).unwrap();
        let deadline = std::time::Instant::now() + Duration::from_secs(5);
        loop {
            let value = session.poll(0).unwrap();
            if !value.is_empty() {
                let state: Value = serde_json::from_str(&value).unwrap();
                if state["phase"] == "preview" {
                    assert_eq!(state["screen"]["columns"], 93);
                    assert_eq!(state["screen"]["rows"], 31);
                    assert!(!state["controlled"].as_bool().unwrap());
                    assert!(
                        session
                            .poll(state["revision"].as_u64().unwrap())
                            .unwrap()
                            .is_empty()
                    );
                    break;
                }
            }
            assert!(std::time::Instant::now() < deadline);
            std::thread::sleep(Duration::from_millis(10));
        }
    }

    fn wait(session: &Session, predicate: impl Fn(&Value) -> bool) -> Value {
        let deadline = std::time::Instant::now() + Duration::from_secs(15);
        loop {
            let text = session.poll(0).unwrap();
            if !text.is_empty() {
                let value: Value = serde_json::from_str(&text).unwrap();
                if predicate(&value) {
                    return value;
                }
                assert!(
                    std::time::Instant::now() < deadline,
                    "Actor timed out: {}",
                    value
                );
            }
            std::thread::sleep(Duration::from_millis(20));
        }
    }
    fn text(state: &Value) -> String {
        frame_text(&state["screen"])
    }
    fn frame_text(frame: &Value) -> String {
        frame["cells"]
            .as_array()
            .into_iter()
            .flatten()
            .filter(|cell| cell[6].as_u64() != Some(0))
            .map(|cell| cell[2].as_str().unwrap_or(""))
            .collect()
    }
    #[test]
    #[ignore = "requires an explicitly supplied owned Rex server"]
    fn live_tab_claims_preserve_size_allow_takeover_and_release_when_leaving() {
        let server = std::env::var("REX_TEST_SERVER").expect("set REX_TEST_SERVER");
        let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        let mut desktop = runtime.block_on(Control::connect(
            Endpoint::new(&server, Duration::from_secs(10)).unwrap(), None, Trace::default(),
        )).unwrap();
        let desktop_id = desktop.client_id().unwrap().to_owned();
        let window = json!({"layout":{"block":{"flavor":"com.superlogical.terminal.shell"}}});
        let created = runtime.block_on(desktop.call("session.create", json!({
            "label":format!("rexling-tab-claims-test-{}", std::process::id()),"initial_windows":[window,window,window]
        }))).unwrap();
        let session = created["session_id"].as_str().unwrap();
        let block = |index: usize| created["initial_windows"][index]["block_ids"][0].as_str().unwrap();
        let (first, second, untouched) = (block(0), block(1), block(2));
        let mut fixtures = vec![session.to_owned()];
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            runtime.block_on(desktop.attach(session)).unwrap();
            let owner = |desktop: &mut Control, target: &str| {
                let view = runtime.block_on(desktop.call("session.view", json!({"session_id":session}))).unwrap();
                view["windows"].as_array().unwrap().iter()
                    .flat_map(|w| w["layers"].as_array().unwrap())
                    .flat_map(|l| l["blocks"].as_array().unwrap())
                    .find(|b| b["block_id"] == target).unwrap()["owner"].as_str().unwrap_or("").to_owned()
            };
            let app = Session::spawn().unwrap();
            app.command(json!({"type":"connect","server":server})).unwrap();
            wait(&app, |v| v["phase"] == "connected");
            app.command(json!({"type":"session","id":session,"block":first})).unwrap();
            let select = |target: &str| {
                app.command(json!({"type":"block","id":target})).unwrap();
                wait(&app, |v| v["selectedBlock"] == target && v["screen"].is_object())
            };
            let take = |columns: u16, rows: u16| {
                app.command(json!({"type":"take","columns":columns,"rows":rows})).unwrap();
                wait(&app, |v| v["controlled"] == true && v["screen"]["columns"] == columns && v["screen"]["rows"] == rows);
            };
            wait(&app, |v| v["selectedBlock"] == first && v["screen"].is_object());
            take(61,23);
            let mobile_id = owner(&mut desktop, first);
            assert!(!mobile_id.is_empty());
            select(second);
            take(71,27);
            assert_eq!(owner(&mut desktop, first),mobile_id);
            assert_eq!(owner(&mut desktop, second),mobile_id);
            assert_eq!(owner(&mut desktop, untouched),"");
            let returned = select(first);
            assert_eq!(returned["controlled"],true);
            assert_eq!(returned["screen"]["columns"],61);
            assert_eq!(returned["screen"]["rows"],23);

            select(second);
            runtime.block_on(desktop.call("session.own_block",json!({"session_id":session,"block_ids":[first],"mode":"take"}))).unwrap();
            runtime.block_on(desktop.terminal(session,first,"resize",json!({"columns":94,"rows":32}))).unwrap();
            assert_eq!(owner(&mut desktop, first),desktop_id);
            assert_eq!(owner(&mut desktop, second),mobile_id);
            app.command(json!({"type":"session","id":session,"block":first})).unwrap();
            let handed_over = wait(&app, |v| v["selectedBlock"] == first && v["screen"].is_object());
            assert_eq!(handed_over["controlled"],false);
            assert_eq!(handed_over["screen"]["columns"],94);
            assert_eq!(owner(&mut desktop, second),mobile_id);

            let other = runtime.block_on(desktop.call("session.create",json!({
                "label":format!("rexling-tab-claims-destination-{}", std::process::id()),"initial_windows":[window]
            }))).unwrap();
            let other_session = other["session_id"].as_str().unwrap();
            fixtures.push(other_session.to_owned());
            app.command(json!({"type":"session","id":other_session})).unwrap();
            wait(&app, |v| v["selectedSession"] == other_session);
            assert_eq!(owner(&mut desktop, first),desktop_id);
            assert_eq!(owner(&mut desktop, second),"");

            app.command(json!({"type":"session","id":session,"block":second})).unwrap();
            wait(&app, |v| v["selectedBlock"] == second && v["screen"].is_object());
            take(71,27);
            select(untouched);
            take(72,28);
            app.command(json!({"type":"pause"})).unwrap();
            wait(&app, |v| v["phase"] == "paused");
            assert_eq!(owner(&mut desktop, first),desktop_id);
            assert_eq!(owner(&mut desktop, second),"");
            assert_eq!(owner(&mut desktop, untouched),"");
        }));
        for session in fixtures {
            runtime.block_on(desktop.attach(&session)).unwrap();
            runtime.block_on(desktop.call("session.destroy", json!({"session_id":session}))).unwrap();
        }
        runtime.block_on(desktop.close()).unwrap();
        result.unwrap();
    }
    #[test]
    #[ignore = "requires an explicitly supplied owned Rex server"]
    fn live_actor_connects_types_and_recovers_after_lifecycle_pause() {
        let server = std::env::var("REX_TEST_SERVER").expect("set REX_TEST_SERVER");
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        let mut writer = runtime
            .block_on(Control::connect(
                Endpoint::new(&server, Duration::from_secs(10)).unwrap(),
                None,
                Trace::default(),
            ))
            .unwrap();
        let script = r#"stty -echo; i=0; while [ "$i" -lt 80 ]; do printf 'SCROLL_%03d\n' "$i"; i=$((i+1)); done; printf 'ACTOR_READY\n'; while IFS= read -r line; do printf 'ACTOR_REPLY:%s\n' "$line"; done"#;
        let created=runtime.block_on(writer.call("session.create",json!({"label":format!("rex-android-host-test-{}",std::process::id()),"initial_windows":[{"layout":{"block":{"flavor":"com.superlogical.terminal.shell","options":{"shell":"none","command":["/bin/sh","-c",script]}}}}]}))).unwrap();
        let id = created["session_id"].as_str().unwrap();
        let block = created["initial_windows"][0]["block_ids"][0]
            .as_str()
            .unwrap();
        runtime.block_on(writer.attach(id)).unwrap();
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let app = Session::spawn().unwrap();
            app.command(json!({"type":"connect","server":server}))
                .unwrap();
            wait(&app, |v| v["phase"] == "connected");
            app.command(json!({"type":"session","id":id})).unwrap();
            wait(&app, |v| {
                v["selectedSession"] == id && !v["windows"].as_array().unwrap().is_empty()
            });
            app.command(json!({"type":"block","id":block,"navigationId":17}))
                .unwrap();
            wait(&app, |v| {
                text(v).contains("ACTOR_READY") && v["navigationId"] == 17
            });
            app.command(json!({"type":"scroll","block":block,"lines":-10000}))
                .unwrap();
            let history = wait(&app, |v| {
                text(v).contains("SCROLL_000") && v["screen"]["scrollbar"][1] == 0
            });
            assert_eq!(history["controlled"], false);
            runtime
                .block_on(writer.terminal(
                    id,
                    block,
                    "write",
                    json!({"data":"while-reading-history\n"}),
                ))
                .unwrap();
            wait(&app, |v| {
                text(v).contains("SCROLL_000")
                    && v["screen"]["scrollbar"][0].as_u64()
                        > history["screen"]["scrollbar"][0].as_u64()
            });
            app.command(json!({"type":"take"})).unwrap();
            wait(&app, |v| v["controlled"] == true);
            app.command(json!({"type":"input","text":"hello-λ🦀\n"}))
                .unwrap();
            wait(&app, |v| text(v).contains("ACTOR_REPLY:hello-λ🦀"));
            app.command(json!({"type":"resize","columns":72,"rows":22}))
                .unwrap();
            wait(&app, |v| {
                v["screen"]["columns"] == 72 && v["screen"]["rows"] == 22
            });
            app.command(json!({"type":"pause"})).unwrap();
            wait(&app, |v| v["phase"] == "paused" && v["controlled"] == false);
            runtime
                .block_on(writer.terminal(id, block, "write", json!({"data":"during-app-pause\n"})))
                .unwrap();
            app.command(json!({"type":"resume"})).unwrap();
            wait(&app, |v| {
                v["phase"] == "connected" && text(v).contains("ACTOR_REPLY:during-app-pause")
            });
            for index in 0..4 {
                // Canonical control events must also notice a different client's takeover.
                runtime.block_on(writer.call("session.own_block",json!({"session_id":id,"block_ids":[block],"mode":"take"}))).unwrap();
                wait(&app,|v|v["controlled"]==false);
                app.command(json!({"type":"pause"})).unwrap();wait(&app,|v|v["phase"]=="paused");
                app.command(json!({"type":"resume"})).unwrap();
                wait(&app,|v|v["phase"]=="connected" && v["screen"].is_object());
                app.command(json!({"type":"resize","session":id,"block":block,"navigationId":17,"columns":60,"rows":26})).unwrap();
                app.command(json!({"type":"take","session":id,"block":block,"navigationId":17,"columns":60,"rows":26})).unwrap();
                wait(&app,|v|v["controlled"]==true && v["screen"]["columns"]==60);
                app.command(json!({"type":"input","text":format!("RECONNECTED_{index}\n")})).unwrap();
                let typed=wait(&app,|v|text(v).contains(&format!("ACTOR_REPLY:RECONNECTED_{index}")));
                assert_eq!(typed["controlled"],true);assert_eq!(typed["error"],"");
            }
            app.command(json!({"type":"release"})).unwrap();
            wait(&app, |v| v["controlled"] == false);
            app.command(json!({"type":"disconnect"})).unwrap();
            wait(&app, |v| v["phase"] == "disconnected");
        }));
        runtime
            .block_on(writer.call("session.destroy", json!({"session_id":id})))
            .unwrap();
        runtime.block_on(writer.close()).unwrap();
        result.unwrap();
    }
    #[test]
    #[ignore = "requires an explicitly supplied owned Rex server with nvim"]
    fn live_neovim_theme_follows_ownership_and_survives_pause() {
        let server = std::env::var("REX_TEST_SERVER").expect("set REX_TEST_SERVER");
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        let mut writer = runtime
            .block_on(Control::connect(
                Endpoint::new(&server, Duration::from_secs(10)).unwrap(),
                None,
                Trace::default(),
            ))
            .unwrap();
        let mut nvim_command=vec![std::env::var("REX_TEST_NVIM_BIN").unwrap_or_else(|_|"nvim".into())];
        if std::env::var_os("REX_TEST_NVIM_CONFIGURED").is_none(){nvim_command.push("--clean".into());}
        let indicator=if std::env::var_os("REX_TEST_NVIM_CONFIGURED").is_some() {
            "lua vim.defer_fn(function() local function bg() vim.api.nvim_buf_set_lines(0,0,-1,false,{'BG='..vim.o.background}) end; vim.api.nvim_create_autocmd('OptionSet',{pattern='background',callback=bg}); bg() end,1500)"
        } else {"set laststatus=2 statusline=BG=%{&background}"};
        nvim_command.extend(["-n","-i","NONE","-c",indicator].map(str::to_owned));
        let created = runtime.block_on(writer.call("session.create", json!({
            "label":format!("rex-android-nvim-test-{}",std::process::id()),
            "initial_windows":[{"layout":{"block":{"flavor":"com.superlogical.terminal.shell",
                "options":{"shell":"none","command":nvim_command}}}}]
        }))).unwrap();
        let id = created["session_id"].as_str().unwrap();
        let block = created["initial_windows"][0]["block_ids"][0]
            .as_str()
            .unwrap();
        runtime.block_on(writer.attach(id)).unwrap();
        let theme = |light: bool| {
            json!({"scheme":if light {"light"} else {"dark"},
            "background":if light {"#ffffff"} else {"#11120f"},
            "foreground":if light {"#202432"} else {"#d9dccf"}})
        };
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            runtime
                .block_on(writer.call(
                    "session.own_block",
                    json!({"session_id":id,"block_ids":[block],"mode":"take"}),
                ))
                .unwrap();
            runtime
                .block_on(writer.terminal(id, block, "set_theme", theme(false)))
                .unwrap();
            runtime.block_on(writer.call("session.disown_block",json!({"session_id":id,"block_ids":[block]}))).unwrap();
            let app = Session::spawn().unwrap();
            // Store the resolved appearance before connecting, as Compose does.
            app.command(json!({"type":"theme","args":theme(true)}))
                .unwrap();
            app.command(json!({"type":"connect","server":server}))
                .unwrap();
            wait(&app, |v| v["phase"] == "connected");
            app.command(json!({"type":"session","id":id})).unwrap();
            wait(&app, |v| v["selectedSession"] == id);
            app.command(json!({"type":"block","id":block})).unwrap();
            wait(&app, |v| text(v).contains("BG=dark"));
            app.command(json!({"type":"take"})).unwrap();
            wait(&app, |v| {
                v["controlled"] == true && text(v).contains("BG=light")
            });
            if std::env::var_os("REX_TEST_NVIM_CONFIGURED").is_some() {
                // Simulate a stale editor while the canonical terminal is already light.
                // Repeated focus-in and set_theme can both be deduplicated here.
                app.command(json!({"type":"input","text":":set background=dark\r"})).unwrap();
                wait(&app, |v| text(v).contains("BG=dark"));
                runtime.block_on(writer.call("session.own_block",json!({"session_id":id,"block_ids":[block],"mode":"take"}))).unwrap();
                wait(&app, |v| v["controlled"]==false);
                app.command(json!({"type":"take","columns":63,"rows":35})).unwrap();
                wait(&app, |v| v["controlled"]==true && text(v).contains("BG=light") && v["screen"]["columns"]==63);
            }
            app.command(json!({"type":"theme","args":theme(false)}))
                .unwrap();
            wait(&app, |v| text(v).contains("BG=dark"));
            app.command(json!({"type":"pause"})).unwrap();
            wait(&app, |v| v["phase"] == "paused");
            // Another client owns the terminal while Android is suspended.
            runtime
                .block_on(writer.call(
                    "session.own_block",
                    json!({"session_id":id,"block_ids":[block],"mode":"take"}),
                ))
                .unwrap();
            runtime
                .block_on(writer.terminal(id, block, "set_theme", theme(false)))
                .unwrap();
            app.command(json!({"type":"theme","args":theme(true)}))
                .unwrap();
            app.command(json!({"type":"resume"})).unwrap();
            wait(&app, |v| {
                v["phase"] == "connected" && text(v).contains("BG=dark")
            });
            app.command(json!({"type":"take"})).unwrap();
            wait(&app, |v| {
                v["controlled"] == true && text(v).contains("BG=light")
            });
            app.command(json!({"type":"theme","args":theme(false)}))
                .unwrap();
            wait(&app, |v| text(v).contains("BG=dark"));
            // Command-session foreground metadata on the tested Mac server reports
            // /usr/bin/login. Keep strict recognition coverage on the Linux fixture.
            if std::env::var_os("REX_TEST_NVIM_BIN").is_none() {wait(&app, |v| {
                v["programs"][block].as_array().is_some_and(|a| {
                    a.iter().any(|n| {
                        n.as_str()
                            .is_some_and(|n| n == "nvim" || n.ends_with("/nvim"))
                    })
                })
            });
            }
            app.command(json!({"type":"disconnect"})).unwrap();
            wait(&app, |v| v["phase"] == "disconnected");
        }));
        runtime
            .block_on(writer.call("session.destroy", json!({"session_id":id})))
            .unwrap();
        runtime.block_on(writer.close()).unwrap();
        result.unwrap();
    }
    #[test]
    #[ignore = "requires an explicitly supplied owned Rex server"]
    fn live_program_icons_follow_reports_in_selected_and_background_tabs() {
        let server = std::env::var("REX_TEST_SERVER").expect("set REX_TEST_SERVER");
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        let mut writer = runtime
            .block_on(Control::connect(
                Endpoint::new(&server, Duration::from_secs(10)).unwrap(),
                None,
                Trace::default(),
            ))
            .unwrap();
        let script = r#"stty -echo; printf '\033]7501;state=idle:app=claude-code:title=Rmlyc3QgdGFzaw==\007ICON_READY\n'; while IFS= read -r line; do case "$line" in codex) printf '\033]7501;state=working:app=codex:title=SW52ZXN0aWdhdGUgUmV4IHByb3RvY29s\007';; frame1) printf '\033]2;⠋ Investigate Rex protocol\007';; frame2) printf '\033]2;⠙ Investigate Rex protocol\007';; frame3) printf '\033]2;⠹ Investigate Rex protocol\007';; clear) printf '\033]7501;state=clear\007';; esac; done"#;
        let created=runtime.block_on(writer.call("session.create",json!({
            "label":format!("rex-android-program-test-{}",std::process::id()),
            "initial_windows":[{"layout":{"block":{"flavor":"com.superlogical.terminal.shell","options":{"shell":"none","command":["/bin/sh","-c",script]}}}},
                {"layout":{"block":{"flavor":"com.superlogical.terminal.shell"}}}]
        }))).unwrap();
        let id = created["session_id"].as_str().unwrap();
        let first = created["initial_windows"][0]["block_ids"][0]
            .as_str()
            .unwrap();
        let second = created["initial_windows"][1]["block_ids"][0]
            .as_str()
            .unwrap();
        runtime.block_on(writer.attach(id)).unwrap();
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let app = Session::spawn().unwrap();
            app.command(json!({"type":"connect","server":server}))
                .unwrap();
            wait(&app, |v| v["phase"] == "connected");
            app.command(json!({"type":"session","id":id})).unwrap();
            wait(&app, |v| v["selectedSession"] == id);
            app.command(json!({"type":"block","id":first})).unwrap();
            wait(&app, |v| {
                text(v).contains("ICON_READY") && v["programs"][first][0] == "claude-code" && v["titles"][first]=="First task"
            });
            runtime
                .block_on(writer.terminal(id, first, "write", json!({"data":"codex\n"})))
                .unwrap();
            wait(&app, |v| v["programs"][first][0] == "codex" && v["titles"][first]=="Investigate Rex protocol" && v["working"].as_array().is_some_and(|ids|ids.iter().any(|id|id==first)));
            for (command,title) in [("frame1","⠋ Investigate Rex protocol"),("frame2","⠙ Investigate Rex protocol"),("frame3","⠹ Investigate Rex protocol")] {
                let started=std::time::Instant::now();
                runtime.block_on(writer.terminal(id,first,"write",json!({"data":format!("{command}\n")}))).unwrap();
                wait(&app,|v|v["titles"][first]==title);
                assert!(started.elapsed()<Duration::from_millis(700),"Title was delayed until metadata polling");
            }
            app.command(json!({"type":"block","id":second})).unwrap();
            wait(&app, |v| {
                v["selectedBlock"] == second && !v["screen"].is_null()
            });
            runtime.block_on(writer.terminal(id,first,"write",json!({"data":"frame1\n"}))).unwrap();
            wait(&app,|v|v["titles"][first]=="⠋ Investigate Rex protocol" && v["selectedBlock"]==second);
            runtime
                .block_on(writer.terminal(id, first, "write", json!({"data":"clear\n"})))
                .unwrap();
            let updated = wait(&app, |v| {
                v["programs"][first].as_array().is_some_and(|a| {
                    !a.is_empty() && a.iter().all(|x| x != "codex" && x != "claude-code")
                })
            });
            assert_eq!(updated["selectedBlock"], second);
            assert!(!updated["working"].as_array().unwrap().iter().any(|id|id==first));
            assert_eq!(updated["controlled"], false);
            app.command(json!({"type":"disconnect"})).unwrap();
            wait(&app, |v| v["phase"] == "disconnected");
        }));
        runtime
            .block_on(writer.call("session.destroy", json!({"session_id":id})))
            .unwrap();
        runtime.block_on(writer.close()).unwrap();
        result.unwrap();
    }
    #[test]
    #[ignore = "requires two explicitly supplied owned Rex servers"]
    fn live_two_servers_isolate_input_selection_and_lifecycle() {
        let endpoints = [
            std::env::var("REX_TEST_SERVER").expect("set REX_TEST_SERVER"),
            std::env::var("REX_TEST_SECOND_SERVER").expect("set REX_TEST_SECOND_SERVER"),
        ];
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        let mut writers: Vec<Control> = endpoints
            .iter()
            .map(|server| {
                runtime
                    .block_on(Control::connect(
                        Endpoint::new(server, Duration::from_secs(10)).unwrap(),
                        None,
                        Trace::default(),
                    ))
                    .unwrap()
            })
            .collect();
        let mut created = Vec::new();
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            for (index, writer) in writers.iter_mut().enumerate() {
                let script = format!(
                    "stty -echo; printf 'HOST_{index}_READY\\n'; while IFS= read -r line; do printf 'HOST_{index}_REPLY:%s\\n' \"$line\"; done"
                );
                let value=runtime.block_on(writer.call("session.create",json!({"label":format!("rexlet-multi-test-{}-{index}",std::process::id()),"initial_windows":[{"layout":{"block":{"flavor":"com.superlogical.terminal.shell","options":{"shell":"none","command":["/bin/sh","-c",script]}}}}]}))).unwrap();
                let id = value["session_id"].as_str().unwrap().to_owned();
                runtime.block_on(writer.attach(&id)).unwrap();
                created.push(value);
            }
            let apps = [Session::spawn().unwrap(), Session::spawn().unwrap()];
            for (index, app) in apps.iter().enumerate() {
                app.command(json!({"type":"connect","server":endpoints[index]}))
                    .unwrap();
                wait(app, |v| v["phase"] == "connected");
                app.command(json!({"type":"session","id":created[index]["session_id"]}))
                    .unwrap();
                wait(app, |v| {
                    v["selectedSession"] == created[index]["session_id"]
                });
                app.command(json!({"type":"block","id":created[index]["initial_windows"][0]["block_ids"][0]})).unwrap();
                wait(app, |v| text(v).contains(&format!("HOST_{index}_READY")));
                app.command(json!({"type":"take"})).unwrap();
                wait(app, |v| v["controlled"] == true);
            }
            apps[0]
                .command(json!({"type":"input","text":"alpha\n"}))
                .unwrap();
            apps[1]
                .command(json!({"type":"input","text":"beta\n"}))
                .unwrap();
            let a = wait(&apps[0], |v| text(v).contains("HOST_0_REPLY:alpha"));
            let b = wait(&apps[1], |v| text(v).contains("HOST_1_REPLY:beta"));
            assert_eq!(a["selectedSession"], created[0]["session_id"]);
            assert_eq!(b["selectedSession"], created[1]["session_id"]);
            assert!(!text(&a).contains("HOST_1_REPLY"));
            assert!(!text(&b).contains("HOST_0_REPLY"));
            apps[0].command(json!({"type":"visibility","visible":false})).unwrap();
            apps[0].command(json!({"type":"input","text":"hidden-update\n"})).unwrap();
            std::thread::sleep(Duration::from_millis(150));
            let hidden:Value=serde_json::from_str(&apps[0].poll(0).unwrap()).unwrap();
            assert!(!text(&hidden).contains("HOST_0_REPLY:hidden-update"));
            apps[0].command(json!({"type":"visibility","visible":true})).unwrap();
            let visible=wait(&apps[0],|v|v["controlled"]==false);
            assert!(!text(&visible).contains("HOST_0_REPLY:hidden-update"));
            apps[0].command(json!({"type":"pause"})).unwrap();
            wait(&apps[0], |v| v["phase"] == "paused");
            apps[1]
                .command(json!({"type":"input","text":"still-connected\n"}))
                .unwrap();
            wait(&apps[1], |v| {
                v["phase"] == "connected" && text(v).contains("HOST_1_REPLY:still-connected")
            });
            apps[0].command(json!({"type":"resume"})).unwrap();
            wait(&apps[0], |v| {
                v["phase"] == "connected" && text(v).contains("HOST_0_REPLY:alpha")
            });
            apps[0].command(json!({"type":"disconnect"})).unwrap();
            wait(&apps[0], |v| v["phase"] == "disconnected");
            apps[1]
                .command(json!({"type":"input","text":"after-other-disconnect\n"}))
                .unwrap();
            wait(&apps[1], |v| {
                text(v).contains("HOST_1_REPLY:after-other-disconnect")
            });
            apps[1].command(json!({"type":"disconnect"})).unwrap();
            wait(&apps[1], |v| v["phase"] == "disconnected");
        }));
        for (index, value) in created.iter().enumerate() {
            runtime
                .block_on(
                    writers[index]
                        .call("session.destroy", json!({"session_id":value["session_id"]})),
                )
                .unwrap();
        }
        for writer in &mut writers {
            runtime.block_on(writer.close()).unwrap();
        }
        result.unwrap();
    }
    #[test]
    #[ignore = "requires an explicitly supplied owned Rex server"]
    fn live_actor_creates_tabs_and_panes_and_rejects_stale_targets() {
        let server = std::env::var("REX_TEST_SERVER").expect("set REX_TEST_SERVER");
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        let mut writer = runtime
            .block_on(Control::connect(
                Endpoint::new(&server, Duration::from_secs(10)).unwrap(),
                None,
                Trace::default(),
            ))
            .unwrap();
        let created = runtime.block_on(writer.call("session.create", json!({
            "label":format!("rex-android-create-test-{}", std::process::id()),
            "initial_windows":[{"window_label":"Original", "layout":{"block":{"flavor":"com.superlogical.terminal.shell"}}}]
        }))).unwrap();
        let id = created["session_id"].as_str().unwrap();
        let original = created["initial_windows"][0]["block_ids"][0]
            .as_str()
            .unwrap();
        runtime.block_on(writer.attach(id)).unwrap();
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let app = Session::spawn().unwrap();
            app.command(json!({"type":"connect","server":server}))
                .unwrap();
            wait(&app, |v| v["phase"] == "connected");
            app.command(json!({"type":"session","id":id})).unwrap();
            wait(&app, |v| v["selectedSession"] == id);
            app.command(json!({"type":"block","id":original})).unwrap();
            wait(&app, |v| !v["screen"].is_null());
            app.command(json!({"type":"new_tab","session":"stale-session"}))
                .unwrap();
            wait(&app, |v| {
                v["error"]
                    .as_str()
                    .unwrap_or("")
                    .contains("selected session changed")
            });
            app.command(json!({"type":"new_tab","session":id})).unwrap();
            let tab = wait(&app, |v| {
                v["windows"].as_array().is_some_and(|w| w.len() == 2)
                    && v["selectedBlock"] != original
                    && !v["screen"].is_null()
                    && v["creating"] == false
            });
            let new_block = tab["selectedBlock"].as_str().unwrap();
            app.command(json!({"type":"new_pane","session":id,"anchor":"stale-pane"}))
                .unwrap();
            wait(&app, |v| {
                v["error"]
                    .as_str()
                    .unwrap_or("")
                    .contains("pane is no longer available")
            });
            app.command(json!({"type":"new_pane","session":id,"anchor":new_block}))
                .unwrap();
            let pane = wait(&app, |v| {
                v["windows"].as_array().is_some_and(|windows| {
                    windows
                        .iter()
                        .any(|w| w["blocks"].as_array().is_some_and(|b| b.len() == 2))
                }) && v["selectedBlock"] != new_block
                    && !v["screen"].is_null()
                    && v["creating"] == false
            });
            assert_eq!(pane["windows"][0]["blocks"].as_array().unwrap().len(), 1);
            assert_eq!(pane["windows"][1]["blocks"].as_array().unwrap().len(), 2);
            assert!(pane["error"].as_str().unwrap().is_empty());
            app.command(json!({"type":"peek","session":id,"ids":[original]}))
                .unwrap();
            let preview = wait(&app, |v| !v["previews"][original].is_null());
            assert_eq!(preview["selectedBlock"], pane["selectedBlock"]);
            assert_eq!(preview["controlled"], pane["controlled"]);
            app.command(json!({"type":"sessions_info"})).unwrap();
            let summary = wait(&app, |v| v["sessionDetails"][id]["tabs"] == 2);
            assert_eq!(summary["sessionDetails"][id]["panes"], 3);
            assert_eq!(summary["sessionDetails"][id]["attachment"], "this device");
            let metadata = wait(&app, |v| {
                v["programs"].as_object().is_some_and(|p| p.len() == 3)
            });
            assert_eq!(metadata["selectedBlock"], pane["selectedBlock"]);
            assert_eq!(metadata["controlled"], pane["controlled"]);
            assert_eq!(metadata["directories"].as_object().unwrap().len(), 3);

            app.command(json!({"type":"new_session","label":format!("rex-android-new-session-test-{}",std::process::id())})).unwrap();
            let fresh = wait(&app, |v| {
                v["selectedSession"] != id
                    && !v["selectedSession"].as_str().unwrap_or("").is_empty()
                    && !v["screen"].is_null()
                    && v["creating"] == false
            });
            assert_eq!(fresh["windows"].as_array().unwrap().len(), 1);
            assert_eq!(fresh["windows"][0]["blocks"].as_array().unwrap().len(), 1);
            app.command(json!({"type":"disconnect"})).unwrap();
            wait(&app, |v| v["phase"] == "disconnected");
        }));
        let listed = runtime
            .block_on(writer.call("session.list", json!({})))
            .unwrap();
        let label = format!("rex-android-new-session-test-{}", std::process::id());
        for session in listed["sessions"].as_array().into_iter().flatten() {
            if session["label"] == label {
                let fresh = session["session_id"].as_str().unwrap();
                runtime.block_on(writer.attach(fresh)).unwrap();
                runtime
                    .block_on(writer.call("session.destroy", json!({"session_id":fresh})))
                    .unwrap();
            }
        }
        runtime
            .block_on(writer.call("session.destroy", json!({"session_id":id})))
            .unwrap();
        runtime.block_on(writer.close()).unwrap();
        result.unwrap();
    }

    #[test]
    #[ignore = "requires an explicitly supplied owned Rex server"]
    fn live_pane_catalog_lists_background_sessions_without_changing_focus() {
        let server = std::env::var("REX_TEST_SERVER").expect("set REX_TEST_SERVER");
        let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        let mut writer = runtime.block_on(Control::connect(
            Endpoint::new(&server, Duration::from_secs(10)).unwrap(), None, Trace::default(),
        )).unwrap();
        let fixtures: Vec<_> = ["foreground", "background"].into_iter().map(|label| {
            let session = runtime.block_on(writer.call("session.create", json!({
                "label":format!("rex-android-catalog-{}-{label}",std::process::id()),
                "initial_windows":[{"layout":{"block":{"flavor":"com.superlogical.terminal.shell"}}}]
            }))).unwrap();
            (
                session["session_id"].as_str().unwrap().to_owned(),
                session["initial_windows"][0]["block_ids"][0].as_str().unwrap().to_owned(),
            )
        }).collect();
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let (foreground, active_block) = &fixtures[0];
            let (background, target_block) = &fixtures[1];
            // Rex can defer starting a new terminal until it has been opened.
            let second = Session::spawn().unwrap();
            second.command(json!({"type":"connect","server":server})).unwrap();
            wait(&second, |v| v["phase"] == "connected");
            second.command(json!({"type":"session","id":background,"block":target_block})).unwrap();
            wait(&second, |v| v["selectedBlock"] == *target_block &&
                v["directories"][target_block].as_str().is_some_and(|cwd| !cwd.is_empty()));
            let app = Session::spawn().unwrap();
            app.command(json!({"type":"connect","server":server})).unwrap();
            wait(&app, |v| v["phase"] == "connected");
            app.command(json!({"type":"session","id":foreground,"block":active_block,"navigationId":16})).unwrap();
            let active = wait(&app, |v| v["selectedBlock"] == *active_block && v["screen"].is_object());
            app.command(json!({"type":"pane_catalog","open":true})).unwrap();
            let catalog = wait(&app, |v| v["paneCatalog"]["items"].is_array() && v["paneCatalogLoading"] == false);
            let entries = catalog["paneCatalog"]["items"].as_array().unwrap();
            assert!(entries.iter().any(|v| v["session"] == *foreground && v["block"] == *active_block));
            assert!(entries.iter().any(|v| v["session"] == *background && v["block"] == *target_block &&
                v["directory"].as_str().is_some_and(|cwd| !cwd.is_empty())), "Missing background pane metadata: {entries:?}");
            assert_eq!(catalog["selectedBlock"], *active_block);
            assert_eq!(catalog["controlled"], active["controlled"]);
            assert_eq!(catalog["screen"]["columns"], active["screen"]["columns"]);
            app.command(json!({"type":"session","id":background,"block":target_block,"navigationId":17})).unwrap();
            wait(&app, |v| v["selectedSession"] == *background && v["selectedBlock"] == *target_block && v["navigationId"] == 17);
            app.command(json!({"type":"pane_catalog","open":false})).unwrap();
            wait(&app, |v| v["paneCatalog"].is_null() && v["paneCatalogLoading"] == false);
        }));
        for (session, _) in &fixtures {
            runtime.block_on(writer.attach(session)).unwrap();
            runtime.block_on(writer.call("session.destroy", json!({"session_id":session}))).unwrap();
        }
        runtime.block_on(writer.close()).unwrap();
        result.unwrap();
    }

    #[test]
    #[ignore = "requires an explicitly supplied owned Rex server"]
    fn live_close_tabs_and_panes_preserves_backgrounds_and_selects_neighbours() {
        let endpoint=std::env::var("REX_TEST_SERVER").expect("set REX_TEST_SERVER");
        let runtime=tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        let mut writer=runtime.block_on(Control::connect(Endpoint::new(&endpoint,Duration::from_secs(10)).unwrap(),None,Trace::default())).unwrap();
        let layout=json!({"block":{"flavor":"com.superlogical.terminal.shell","options":{"shell":"none","command":["/bin/sh","-c","stty -echo; printf CLOSE_READY; cat"]}}});
        let created=runtime.block_on(writer.call("session.create",json!({"label":format!("rexlink-close-terminals-test-{}",std::process::id()),"initial_windows":[{"window_label":"Keep","layout":layout},{"window_label":"Next","layout":layout},{"window_label":"Background","layout":layout}]}))).unwrap();
        let id=created["session_id"].as_str().unwrap();
        let first=created["initial_windows"][0]["block_ids"][0].as_str().unwrap();
        let next=created["initial_windows"][1]["block_ids"][0].as_str().unwrap();
        let background=created["initial_windows"][2]["window_id"].as_str().unwrap();
        runtime.block_on(writer.attach(id)).unwrap();
        let mut extra=Vec::new();
        for _ in 0..2 {
            let split=runtime.block_on(writer.call("session.new_split",json!({"session_id":id,"anchor_id":first,"direction":"horizontal","side":"after","layout":layout,"focus":false}))).unwrap();
            extra.push(split["block_ids"][0].as_str().unwrap().to_owned());
        }
        let result=std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let app=Session::spawn().unwrap();app.command(json!({"type":"connect","server":endpoint})).unwrap();wait(&app,|v|v["phase"]=="connected");
            app.command(json!({"type":"session","id":id})).unwrap();wait(&app,|v|v["selectedSession"]==id);
            app.command(json!({"type":"block","id":first})).unwrap();wait(&app,|v|text(v).contains("CLOSE_READY"));
            app.command(json!({"type":"take"})).unwrap();wait(&app,|v|v["controlled"]==true);
            app.command(json!({"type":"close_pane","session":"stale","id":first})).unwrap();
            wait(&app,|v|v["error"].as_str().is_some_and(|s|s.contains("selected session changed")));
            app.command(json!({"type":"close_tab","session":id,"id":background,"blocks":[]})).unwrap();
            wait(&app,|v|v["error"].as_str().is_some_and(|s|s.contains("tab changed")));
            app.command(json!({"type":"close_tab","session":id,"id":background,"blocks":created["initial_windows"][2]["block_ids"]})).unwrap();
            let kept=wait(&app,|v|v["creating"]==false && v["windows"].as_array().is_some_and(|w|w.len()==2));
            assert_eq!(kept["selectedBlock"],first);assert_eq!(kept["controlled"],true);assert_eq!(kept["error"],"");
            app.command(json!({"type":"input","text":"still-alive\n"})).unwrap();wait(&app,|v|text(v).contains("still-alive"));
            app.command(json!({"type":"close_pane","session":id,"id":extra[0]})).unwrap();
            let kept=wait(&app,|v|v["creating"]==false && v["windows"][0]["blocks"].as_array().is_some_and(|b|b.len()==2));
            assert_eq!(kept["selectedBlock"],first);assert_eq!(kept["controlled"],true);
            app.command(json!({"type":"close_pane","session":id,"id":extra[0]})).unwrap();
            app.command(json!({"type":"close_pane","session":id,"id":first})).unwrap();
            let neighbour=wait(&app,|v|v["creating"]==false && v["selectedBlock"]==extra[1] && !v["screen"].is_null());
            assert_eq!(neighbour["controlled"],false);assert_eq!(neighbour["error"],"");
            let tab=neighbour["windows"][0].clone();
            app.command(json!({"type":"close_tab","session":id,"id":tab["id"],"blocks":[extra[1]]})).unwrap();
            wait(&app,|v|v["creating"]==false && v["selectedBlock"]==next && !v["screen"].is_null());
            app.command(json!({"type":"close_pane","session":id,"id":next})).unwrap();
            let empty=wait(&app,|v|v["creating"]==false && v["windows"].as_array().is_some_and(Vec::is_empty));
            assert!(empty["screen"].is_null());assert_eq!(empty["error"],"");
        }));
        let list=runtime.block_on(writer.call("session.list",json!({}))).unwrap();
        if list["sessions"].as_array().unwrap().iter().any(|s|s["session_id"]==id) {runtime.block_on(writer.call("session.destroy",json!({"session_id":id}))).unwrap();}
        runtime.block_on(writer.close()).unwrap();result.unwrap();
    }

    #[test]
    #[ignore = "requires an explicitly supplied Rex server with Undo Close support"]
    fn live_undo_close_restores_pane_and_last_tab_with_terminal_history() {
        let endpoint=std::env::var("REX_TEST_SERVER").expect("set REX_TEST_SERVER");
        let runtime=tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        let mut writer=runtime.block_on(Control::connect(Endpoint::new(&endpoint,Duration::from_secs(10)).unwrap(),None,Trace::default())).unwrap();
        let layout=json!({"block":{"flavor":"com.superlogical.terminal.shell","options":{"shell":"none",
            "command":["/bin/sh","-c","stty -echo; seq 10100 10220; exec cat"]}}});
        let created=runtime.block_on(writer.call("session.create",json!({"label":"rexling-undo-close-test",
            "initial_windows":[{"layout":{"split":{"direction":"horizontal","before":layout,"after":layout}}}]}))).unwrap();
        let id=&created["session_id"];
        let tab=&created["initial_windows"][0]["window_id"];
        let blocks=&created["initial_windows"][0]["block_ids"];
        let block=&blocks[0];
        let result=std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let app=Session::spawn().unwrap();
            app.command(json!({"type":"connect","server":endpoint})).unwrap();
            wait(&app,|v|v["phase"]=="connected" && v["supportsUndoClose"]==true);
            app.command(json!({"type":"session","id":id,"block":block,"navigationId":1})).unwrap();
            wait(&app,|v|text(v).contains("10220"));
            app.command(json!({"type":"take"})).unwrap();
            wait(&app,|v|v["controlled"]==true);
            app.command(json!({"type":"input","text":"PRESERVED_BEFORE_CLOSE\n"})).unwrap();
            wait(&app,|v|text(v).contains("PRESERVED_BEFORE_CLOSE"));
            let undo_command=|state:&Value|json!({"type":"undo_close","serial":state["undoClose"]["serial"],
                "session":id,"connectionEpoch":state["connectionEpoch"],"navigationId":state["navigationId"]});

            app.command(json!({"type":"close_pane","session":id,"id":block})).unwrap();
            let closed=wait(&app,|v|v["undoClose"]["tab"]==false && v["creating"]==false);
            assert_eq!(closed["selectedBlock"],blocks[1]);
            let undo=undo_command(&closed);
            let mut stale=undo.clone();stale["serial"]=json!(0);
            app.command(stale).unwrap();
            app.command(undo).unwrap();
            let restored=wait(&app,|v|v["selectedBlock"]==*block && text(v).contains("PRESERVED_BEFORE_CLOSE"));
            assert_eq!(restored["windows"][0]["id"],*tab);
            assert!(restored["undoClose"].is_null());

            app.command(json!({"type":"close_tab","session":id,"id":tab,"blocks":blocks})).unwrap();
            let closed=wait(&app,|v|v["undoClose"]["tab"]==true && v["creating"]==false);
            assert_eq!(closed["windows"],json!([]));
            app.command(undo_command(&closed)).unwrap();
            let restored=wait(&app,|v|v["selectedBlock"]==*block && text(v).contains("PRESERVED_BEFORE_CLOSE"));
            assert_eq!(restored["windows"][0]["id"],*tab);
            app.command(json!({"type":"scroll","block":block,"lines":-10000})).unwrap();
            wait(&app,|v|text(v).contains("10100"));
            app.command(json!({"type":"take"})).unwrap();
            wait(&app,|v|v["controlled"]==true);
            app.command(json!({"type":"input","text":"STILL_RUNNING\n"})).unwrap();
            wait(&app,|v|text(v).contains("STILL_RUNNING"));
        }));
        runtime.block_on(writer.attach(id.as_str().unwrap())).unwrap();
        runtime.block_on(writer.call("session.destroy",json!({"session_id":id}))).unwrap();
        runtime.block_on(writer.close()).unwrap();
        result.unwrap();
    }

    #[test]
    #[ignore = "requires an explicitly supplied owned Rex server"]
    fn live_close_session_preserves_other_views_and_closes_current() {
        let endpoint=std::env::var("REX_TEST_SERVER").expect("set REX_TEST_SERVER");
        let runtime=tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        let mut writer=runtime.block_on(Control::connect(Endpoint::new(&endpoint,Duration::from_secs(10)).unwrap(),None,Trace::default())).unwrap();
        let mut created=Vec::new();
        let result=std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            for index in 0..2 {
                let value=runtime.block_on(writer.call("session.create",json!({"label":format!("rexlet-close-test-{}-{index}",std::process::id()),
                    "initial_windows":[{"layout":{"block":{"flavor":"com.superlogical.terminal.shell","options":{"shell":"none","command":["/bin/sh","-c","printf CLOSE_READY; cat"]}}}}]}))).unwrap();
                created.push(value);
            }
            let app=Session::spawn().unwrap();
            app.command(json!({"type":"connect","server":endpoint})).unwrap();wait(&app,|v|v["phase"]=="connected");
            app.command(json!({"type":"session","id":created[0]["session_id"]})).unwrap();
            wait(&app,|v|v["selectedSession"]==created[0]["session_id"]);
            app.command(json!({"type":"block","id":created[0]["initial_windows"][0]["block_ids"][0]})).unwrap();
            wait(&app,|v|text(v).contains("CLOSE_READY"));
            app.command(json!({"type":"close_session","id":created[1]["session_id"]})).unwrap();
            let remaining=wait(&app,|v|v["creating"]==false && v["sessions"].as_array().is_some_and(|list|!list.iter().any(|s|s["session_id"]==created[1]["session_id"])));
            assert_eq!(remaining["selectedSession"],created[0]["session_id"]);assert!(text(&remaining).contains("CLOSE_READY"));
            app.command(json!({"type":"close_session","id":created[0]["session_id"]})).unwrap();
            let empty=wait(&app,|v|v["selectedSession"]=="" && v["creating"]==false);
            assert!(empty["screen"].is_null());assert_eq!(empty["controlled"],false);
            app.command(json!({"type":"close_session","id":created[0]["session_id"]})).unwrap();
            let closed=wait(&app,|v|v["creating"]==false && v["notice"]=="Session closed");
            assert_eq!(closed["error"],"");
        }));
        let sessions=runtime.block_on(writer.call("session.list",json!({}))).unwrap();
        for value in created {
            if sessions["sessions"].as_array().unwrap().iter().any(|s|s["session_id"]==value["session_id"]) {
                runtime.block_on(writer.attach(value["session_id"].as_str().unwrap())).unwrap();
                runtime.block_on(writer.call("session.destroy",json!({"session_id":value["session_id"]}))).unwrap();
            }
        }
        runtime.block_on(writer.close()).unwrap();result.unwrap();
    }

    #[test]
    #[ignore = "requires an explicitly supplied owned server with python3"]
    fn live_mouse_click_delivers_one_press_release_pair_and_drops_stale_or_disabled_taps() {
        let endpoint=std::env::var("REX_TEST_SERVER").expect("set REX_TEST_SERVER");
        let runtime=tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        let mut writer=runtime.block_on(Control::connect(Endpoint::new(&endpoint,Duration::from_secs(10)).unwrap(),None,Trace::default())).unwrap();
        let script=r#"import os,tty,re
tty.setraw(0)
os.write(1,b'\x1b[?1049h\x1b[?1000h\x1b[?1006h\x1b[H\x1b[2JCLICK_READY')
count=0
buffer=b''
while True:
 data=os.read(0,4096)
 if not data:break
 if b'OFF' in data:
  os.write(1,b'\x1b[?1000l\r\nMOUSE_DISABLED')
 buffer+=data
 while True:
  match=re.search(rb'\x1b\[<0;([0-9]+);([0-9]+)M\x1b\[<0;\1;\2m',buffer)
  if not match:break
  count+=1
  os.write(1,('\r\nTAP_COUNT:'+str(count)+' CELL:'+match[1].decode()+','+match[2].decode()+' RELEASED').encode())
  buffer=buffer[match.end():]
 if len(buffer)>4096:buffer=buffer[-128:]
"#;
        let created=runtime.block_on(writer.call("session.create",json!({"label":format!("rexlink-click-test-{}",std::process::id()),
            "initial_windows":[{"layout":{"block":{"flavor":"com.superlogical.terminal.shell","options":{"shell":"none","command":["python3","-u","-c",script]}}}}]}))).unwrap();
        let id=created["session_id"].as_str().unwrap();let block=created["initial_windows"][0]["block_ids"][0].as_str().unwrap();
        let result=std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let app=Session::spawn().unwrap();app.command(json!({"type":"connect","server":endpoint})).unwrap();wait(&app,|v|v["phase"]=="connected");
            app.command(json!({"type":"session","id":id})).unwrap();wait(&app,|v|v["selectedSession"]==id);
            app.command(json!({"type":"block","id":block})).unwrap();wait(&app,|v|text(v).contains("CLICK_READY"));
            app.command(json!({"type":"take"})).unwrap();wait(&app,|v|v["controlled"]==true);
            app.command(json!({"type":"click","col":4,"row":5,"cw":10,"ch":20})).unwrap();
            wait(&app,|v|text(v).contains("TAP_COUNT:1 CELL:5,6 RELEASED"));
            app.command(json!({"type":"click","block":"stale-pane","col":7,"row":8})).unwrap();
            app.command(json!({"type":"input","text":"OFF"})).unwrap();
            wait(&app,|v|text(v).contains("MOUSE_DISABLED") && v["screen"]["input_flags"].as_u64().unwrap()&1==0);
            app.command(json!({"type":"click","col":7,"row":8})).unwrap();
            std::thread::sleep(Duration::from_millis(100));
            let state:Value=serde_json::from_str(&app.poll(0).unwrap()).unwrap();
            assert!(!text(&state).contains("TAP_COUNT:2"));assert_eq!(state["error"],"");
        }));
        runtime.block_on(writer.attach(id)).unwrap();runtime.block_on(writer.call("session.destroy",json!({"session_id":id}))).unwrap();runtime.block_on(writer.close()).unwrap();result.unwrap();
    }

    #[test]
    #[ignore = "requires an explicitly supplied owned server with python3"]
    fn live_wheel_and_scrollback_scroll_the_requested_pane() {
        let endpoint=std::env::var("REX_TEST_SERVER").expect("set REX_TEST_SERVER");
        let runtime=tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        let mut writer=runtime.block_on(Control::connect(Endpoint::new(&endpoint,Duration::from_secs(10)).unwrap(),None,Trace::default())).unwrap();
        let script=r###"import os,tty,re
original=__import__('termios').tcgetattr(0)
tty.setraw(0)
os.write(1,b'\x1b[?1049h\x1b[?1007l\x1b[?1000h\x1b[?1006h\x1b]7501;state=idle:app=codex\x07')
position=0
os.write(1,b'\x1b[H\x1b[2JSCROLL_POSITION:0')
buffer=b''
while True:
 data=os.read(0,4096)
 if not data: break
 buffer+=data
 while True:
  match=re.search(rb'\x1b\[<(64|65);[0-9]+;[0-9]+M',buffer)
  if not match: break
  position+=-1 if match[1]==b'64' else 1
  buffer=buffer[match.end():]
  os.write(1,('\x1b[H\x1b[2JSCROLL_POSITION:'+str(position)).encode())
 if len(buffer)>4096: buffer=buffer[-64:]
"###;
        let mouse_pane=json!({"block":{"flavor":"com.superlogical.terminal.shell","options":{"shell":"none","command":["/usr/bin/python3","-u","-c",script]}}});
        let history_pane=json!({"block":{"flavor":"com.superlogical.terminal.shell","options":{"shell":"none",
            "command":["/bin/sh","-c","i=0; while [ \"$i\" -lt 120 ]; do printf 'HISTORY_%03d\\n' \"$i\"; i=$((i+1)); done; exec cat"]}}});
        let created=runtime.block_on(writer.call("session.create",json!({"label":format!("rexlink-wheel-test-{}",std::process::id()),
            "initial_windows":[{"layout":{"split":{"direction":"horizontal","before":mouse_pane,"after":{
                "split":{"direction":"vertical","before":history_pane,"after":mouse_pane}}}}}]}))).unwrap();
        let id=created["session_id"].as_str().unwrap();
        let window=&created["initial_windows"][0]["window_id"];
        let blocks=&created["initial_windows"][0]["block_ids"];
        let block=blocks[0].as_str().unwrap();
        let history=blocks[1].as_str().unwrap();
        let mirror=blocks[2].as_str().unwrap();
        let result=std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let app=Session::spawn().unwrap();app.command(json!({"type":"connect","server":endpoint})).unwrap();wait(&app,|v|v["phase"]=="connected");
            app.command(json!({"type":"session","id":id})).unwrap();wait(&app,|v|v["selectedSession"]==id);
            app.command(json!({"type":"block","id":block})).unwrap();
            let initial=wait(&app,|v|text(v).contains("SCROLL_POSITION:0"));assert_eq!(initial["controlled"],false);
            assert_eq!(initial["screen"]["scrollbar"][0],initial["screen"]["scrollbar"][2]);assert_eq!(initial["screen"]["input_flags"].as_u64().unwrap()&3,3);
            app.command(json!({"type":"wheel","session":id,"block":block,"lines":-3,"col":4,"row":5,"cw":10,"ch":20})).unwrap();
            wait(&app,|v|v["controlled"]==true && text(v).contains("SCROLL_POSITION:-3"));
            app.command(json!({"type":"wheel","session":id,"block":block,"lines":2,"col":4,"row":5,"cw":10,"ch":20})).unwrap();
            wait(&app,|v|text(v).contains("SCROLL_POSITION:-1"));
            app.command(json!({"type":"wheel","session":id,"block":"stale","lines":-5})).unwrap();
            std::thread::sleep(Duration::from_millis(100));
            let state:Value=serde_json::from_str(&app.poll(0).unwrap()).unwrap();assert!(text(&state).contains("SCROLL_POSITION:-1"));

            let history_request=json!({"block":history,"columns":40,"rows":20});
            app.command(json!({"type":"mirror","session":id,"window":window,"panes":[history_request]})).unwrap();
            let at_bottom=wait(&app,|v|frame_text(&v["previews"][history]).contains("HISTORY_119"));
            let bottom=at_bottom["previews"][history]["scrollbar"][1].as_u64().unwrap();
            assert!(bottom>4);
            let mut scroll=json!({"type":"scroll","mirror":true,"session":id,"window":window,"block":history,
                "navigationId":at_bottom["navigationId"],"connectionEpoch":at_bottom["connectionEpoch"],"lines":-4});
            let mut stale=scroll.clone();
            stale["window"]=json!("old-window");
            app.command(stale).unwrap();
            app.command(scroll.clone()).unwrap();
            let scrolled=wait(&app,|v|v["previews"][history]["scrollbar"][1]==bottom-4);
            assert_eq!(scrolled["selectedBlock"],block);
            assert_eq!(scrolled["controlled"],true);
            assert!(text(&scrolled).contains("SCROLL_POSITION:-1"));
            scroll["bottom"]=json!(true);
            app.command(scroll).unwrap();
            wait(&app,|v|v["previews"][history]["scrollbar"][1]==bottom);

            app.command(json!({"type":"mirror","session":id,"window":window,
                "panes":[history_request,{"block":mirror,"columns":40,"rows":20}]})).unwrap();
            let ready=wait(&app,|v|frame_text(&v["previews"][mirror]).contains("SCROLL_POSITION:0"));
            app.command(json!({"type":"wheel","mirror":true,"session":id,"window":window,"block":mirror,
                "navigationId":ready["navigationId"],"connectionEpoch":ready["connectionEpoch"],
                "lines":-2,"col":4,"row":5,"cw":10,"ch":20})).unwrap();
            let scrolled=wait(&app,|v|frame_text(&v["previews"][mirror]).contains("SCROLL_POSITION:-2"));
            assert_eq!(scrolled["selectedBlock"],block);
            assert_eq!(scrolled["controlled"],true);
            assert!(text(&scrolled).contains("SCROLL_POSITION:-1"));
        }));
        runtime.block_on(writer.attach(id)).unwrap();runtime.block_on(writer.call("session.destroy",json!({"session_id":id}))).unwrap();runtime.block_on(writer.close()).unwrap();result.unwrap();
    }

    #[test]
    #[ignore = "requires an explicitly supplied owned Rex server"]
    fn live_osc8_destinations_survive_snapshot_live_updates_and_resume() {
        let server=std::env::var("REX_TEST_SERVER").expect("set REX_TEST_SERVER");
        let runtime=tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        let mut writer=runtime.block_on(Control::connect(Endpoint::new(&server,Duration::from_secs(10)).unwrap(),None,Trace::default())).unwrap();
        let script=r#"stty -echo; printf '\033]8;;https://example.com/snapshot\007Docs\033]8;;\007 OSC8_READY\n'; while IFS= read -r line; do if [ "$line" = clear ]; then printf '\033[HDocs\nLINKS_CLEARED\n'; else printf '\033[H\033]8;;https://example.com/live\007Docs\033]8;;\007\n'; fi; done"#;
        let created=runtime.block_on(writer.call("session.create",json!({"label":format!("rex-android-osc8-test-{}",std::process::id()),"initial_windows":[{"layout":{"block":{"flavor":"com.superlogical.terminal.shell","options":{"shell":"none","command":["/bin/sh","-c",script]}}}}]}))).unwrap();
        let id=created["session_id"].as_str().unwrap();
        let block=created["initial_windows"][0]["block_ids"][0].as_str().unwrap();
        runtime.block_on(writer.attach(id)).unwrap();
        let result=std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let app=Session::spawn().unwrap();app.command(json!({"type":"connect","server":server})).unwrap();wait(&app,|v|v["phase"]=="connected");
            app.command(json!({"type":"session","id":id})).unwrap();wait(&app,|v|v["selectedSession"]==id);
            app.command(json!({"type":"block","id":block})).unwrap();
            let has=|v:&Value,url:&str|v["screen"]["cells"].as_array().is_some_and(|cells|cells.iter().any(|c|c[7].as_str()==Some(url)));
            wait(&app,|v|has(v,"https://example.com/snapshot"));
            app.command(json!({"type":"take"})).unwrap();wait(&app,|v|v["controlled"]==true);
            app.command(json!({"type":"input","text":"go\n"})).unwrap();wait(&app,|v|has(v,"https://example.com/live"));
            app.command(json!({"type":"pause"})).unwrap();wait(&app,|v|v["phase"]=="paused");
            app.command(json!({"type":"resume"})).unwrap();wait(&app,|v|v["phase"]=="connected" && has(v,"https://example.com/live"));
            app.command(json!({"type":"take"})).unwrap();wait(&app,|v|v["controlled"]==true);
            app.command(json!({"type":"input","text":"clear\n"})).unwrap();wait(&app,|v|text(v).contains("LINKS_CLEARED") && !has(v,"https://example.com/live"));
        }));
        runtime.block_on(writer.call("session.destroy",json!({"session_id":id}))).unwrap();runtime.block_on(writer.close()).unwrap();result.unwrap();
    }

    #[test]
    #[ignore = "requires an explicitly supplied owned Rex server"]
    fn live_activity_collects_sessions_and_opens_the_exact_pane_read_only() {
        let server=std::env::var("REX_TEST_SERVER").expect("set REX_TEST_SERVER");
        let runtime=tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        let mut writer=runtime.block_on(Control::connect(Endpoint::new(&server,Duration::from_secs(10)).unwrap(),None,Trace::default())).unwrap();
        let mut ids=Vec::new();let mut blocks=Vec::new();
        for (index,state) in ["working","done"].iter().enumerate() {
            let script=format!("stty -echo; printf '\\033]7501;state={state}:app=codex:title=Rml4dHVyZSB0YXNr\\007'; printf '\\033]7501;state=blocked:id=worker:kind=permission:msg=UmV2aWV3IHRoaXM=\\007'; printf '\\033]0;Initial terminal title\\007'; printf 'ACTIVITY_READY\\n'; while IFS= read -r line; do if [ \"$line\" = alert ]; then printf '\\033]7501;state=blocked:msg=TmV3IGFwcHJvdmFs\\007'; fi; printf '\\033]0;Updated terminal title\\007'; done");
            let created=runtime.block_on(writer.call("session.create",json!({"label":format!("rex-android-activity-test-{}-{index}",std::process::id()),"initial_windows":[{"layout":{"block":{"flavor":"com.superlogical.terminal.shell","options":{"shell":"none","command":["/bin/sh","-c",script]}}}}]}))).unwrap();
            ids.push(created["session_id"].as_str().unwrap().to_owned());blocks.push(created["initial_windows"][0]["block_ids"][0].as_str().unwrap().to_owned());
        }
        let result=std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            fn until(app:&Session,predicate:impl Fn(&Value)->bool)->Value {
                let deadline=std::time::Instant::now()+Duration::from_secs(20);
                loop {let raw=app.poll(0).unwrap();if !raw.is_empty() {let value:Value=serde_json::from_str(&raw).unwrap();if predicate(&value){return value;}}
                    assert!(std::time::Instant::now()<deadline,"Activity fixture timed out");std::thread::sleep(Duration::from_millis(25));}
            }
            let monitor=Session::spawn().unwrap();monitor.command(json!({"type":"monitor"})).unwrap();
            monitor.command(json!({"type":"connect","server":server})).unwrap();
            let monitored=until(&monitor,|v|v["phase"]=="connected" && v["activity"].as_array().is_some_and(|items|items.iter().any(|a|a["session"]==ids[0] && a["state"]=="blocked")));
            assert_eq!(monitored["selectedSession"],"");assert_eq!(monitored["selectedBlock"],"");
            assert_eq!(monitored["controlled"],false);assert!(monitored["screen"].is_null());
            let app=Session::spawn().unwrap();app.command(json!({"type":"connect","server":server})).unwrap();until(&app,|v|v["phase"]=="connected");
            app.command(json!({"type":"session","id":ids[0],"block":blocks[0],"navigationId":7})).unwrap();
            let initial=until(&app,|v|v["selectedBlock"]==blocks[0] && text(v).contains("ACTIVITY_READY"));
            let closed=until(&app,|v|v["attentionCount"].as_u64().is_some_and(|n|n>=2));
            assert!(closed["activity"].as_array().is_some_and(|items|items.iter().any(|item|
                item["session"]==ids[1] && item["block"]==blocks[1] && item["state"]=="done")),
                "Tab badges need pane-scoped activity records even when the sheet is closed");
            app.command(json!({"type":"activity","open":true})).unwrap();
            let listed=until(&app,|v|v["activity"].as_array().is_some_and(|items|
                items.iter().any(|a|a["session"]==ids[0] && a["state"]=="working") && items.iter().any(|a|a["session"]==ids[1] && a["state"]=="done") && items.iter().any(|a|a["id"]=="worker" && a["state"]=="blocked")));
            assert_eq!(listed["selectedBlock"],blocks[0]);assert_eq!(listed["controlled"],false);
            assert!(listed["activity"].as_array().unwrap().iter().any(|a|a["block"]==blocks[0] && a["id"]=="" && a["title"]=="Initial terminal title"));
            runtime.block_on(writer.attach(&ids[0])).unwrap();
            runtime.block_on(writer.terminal(&ids[0],&blocks[0],"write",json!({"data":"update\n"}))).unwrap();
            until(&app,|v|v["activity"].as_array().is_some_and(|items|items.iter().any(|a|a["block"]==blocks[0] && a["id"]=="" && a["title"]=="Updated terminal title")));
            assert_eq!(listed["screen"]["columns"],initial["screen"]["columns"]);
            let current=until(&app,|v|v["activity"].as_array().is_some_and(|items|!items.is_empty()));
            let seen:serde_json::Map<String,Value>=current["activity"].as_array().unwrap().iter().filter_map(|item|
                Some((item["attentionKey"].as_str()?.to_owned(),item["attentionVersion"].clone()))).collect();
            app.command(json!({"type":"activity_seen","items":seen})).unwrap();
            until(&app,|v|v["attentionCount"]==0);
            app.command(json!({"type":"activity","open":false})).unwrap();
            runtime.block_on(writer.attach(&ids[1])).unwrap();
            runtime.block_on(writer.terminal(&ids[1],&blocks[1],"write",json!({"data":"alert\n"}))).unwrap();
            let background=until(&monitor,|v|v["activity"].as_array().is_some_and(|items|items.iter().any(|a|a["block"]==blocks[1] && a["id"]=="" && a["state"]=="blocked")));
            assert!(background["screen"].is_null());assert_eq!(background["controlled"],false);
            let alerted=until(&app,|v|v["attentionCount"].as_u64().is_some_and(|n|n>0) &&
                v["activity"].as_array().is_some_and(|items|items.iter().any(|item|
                    item["block"]==blocks[1] && item["id"]=="" && item["state"]=="blocked")));
            assert_eq!(alerted["controlled"],false);
            app.command(json!({"type":"activity","open":true})).unwrap();
            until(&app,|v|v["activity"].as_array().is_some_and(|items|items.iter().any(|a|a["block"]==blocks[1] && a["id"]=="" && a["state"]=="blocked")));
            app.command(json!({"type":"session","id":ids[1],"block":blocks[1],"navigationId":8})).unwrap();
            let jumped=until(&app,|v|v["selectedBlock"]==blocks[1] && v["screen"].is_object());
            assert_eq!(jumped["selectedSession"],ids[1]);assert_eq!(jumped["controlled"],false);
            app.command(json!({"type":"activity","open":false})).unwrap();
            app.command(json!({"type":"session","id":ids[1],"block":"missing-pane","navigationId":9})).unwrap();
            let stale=until(&app,|v|v["error"].as_str().is_some_and(|e|e.contains("no longer available")));
            assert_eq!(stale["controlled"],false);
            assert_eq!(stale["selectedBlock"],blocks[1],"A stale Activity link must leave the current pane selected");
        }));
        for id in &ids {runtime.block_on(writer.attach(id)).unwrap();runtime.block_on(writer.call("session.destroy",json!({"session_id":id}))).unwrap();}
        runtime.block_on(writer.close()).unwrap();result.unwrap();
    }

    #[test]
    #[ignore = "host metadata benchmark; run with --ignored --nocapture"]
    fn activity_metadata_publish_benchmark() {
        let shared=Arc::new(Mutex::new(Shared{revision:0,json:String::new(),pending_previews:BTreeMap::new(),frame:None,key:String::new(),wire:crate::updates::WireBase::default(),activity:Arc::default()}));
        let mut actor=Actor::new(shared.clone(),Arc::new(crate::updates::Updates::default()));
        actor.activity=json!((0..256).map(|n|json!({"session":format!("session:{n}"),"block":format!("block:{n}"),"id":"","state":"working","app":"codex","owner":"codex","title":"A reported task title","message":"Work is continuing on this task.","updatedAt":"2026-11-02T00:00:00Z"})).collect::<Vec<_>>());
        for open in [false,true] {
            actor.activity_open=open;let mut samples=Vec::new();let mut bytes=0;
            for i in 0..200 {actor.titles=json!({"pane":format!("Frame {i}")});let start=std::time::Instant::now();actor.publish();let mut state=shared.lock().unwrap();let after=state.revision-1;bytes=crate::updates::encode(&mut state,after).len();samples.push(start.elapsed().as_micros());}
            samples.sort();println!("activity_open={open} records=256 bytes={bytes} median_us={} p95_us={}",samples[100],samples[190]);
        }
    }

    #[test] fn background_monitor_is_read_only_and_publishes_activity_without_a_terminal() {
        let shared=Arc::new(Mutex::new(Shared{revision:0,json:String::new(),pending_previews:BTreeMap::new(),frame:None,key:String::new(),wire:crate::updates::WireBase::default(),activity:Arc::default()}));
        let mut actor=Actor::new(shared.clone(),Arc::new(crate::updates::Updates::default()));
        let runtime=tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        runtime.block_on(actor.command(json!({"type":"monitor"}))).unwrap();
        assert!(actor.monitoring);assert!(!actor.visible);
        for kind in ["session","block","take","resize","theme","create","close","activity","resume","peek"] {
            assert!(runtime.block_on(actor.command(json!({"type":kind}))).is_err(),"{kind}");
        }
        assert!(runtime.block_on(actor.send_input(json!({"type":"input","text":"secret"}))).is_err());
        actor.activity=json!([{"state":"blocked","title":"Needs approval"}]);actor.activity_dirty=true;actor.publish();
        let state=shared.lock().unwrap();
        assert_eq!(state.activity[0]["state"],"blocked");assert!(state.frame.is_none());
        assert!(actor.selected_session.is_empty());assert!(actor.selected_block.is_empty());
        assert!(!actor.controlled && actor.data.is_none() && actor.replica.is_none());
    }

    #[test] fn hidden_activity_is_shared_for_focus_policy_without_recopied_snapshots() {
        let shared=Arc::new(Mutex::new(Shared{revision:0,json:String::new(),pending_previews:BTreeMap::new(),frame:None,key:String::new(),wire:crate::updates::WireBase::default(),activity:Arc::default()}));
        let mut actor=Actor::new(shared.clone(),Arc::new(crate::updates::Updates::default()));
        actor.activity=json!([{"title":"Cached task"}]);actor.publish();
        let published=||{let shared=shared.lock().unwrap();let mut value:Value=serde_json::from_str(&shared.json).unwrap();value["activity"]=shared.activity.as_ref().clone();value};
        assert_eq!(published()["activity"][0]["title"],"Cached task");
        let cached=shared.lock().unwrap().activity.clone();
        actor.activity_open=true;actor.publish();assert_eq!(published()["activity"][0]["title"],"Cached task");
        actor.activity_open=false;actor.publish();assert_eq!(published()["activity"][0]["title"],"Cached task");
        assert!(Arc::ptr_eq(&cached,&shared.lock().unwrap().activity));
    }

    #[test] fn stale_or_hidden_take_and_resize_commands_cannot_target_the_new_pane() {
        let shared=Arc::new(Mutex::new(Shared{revision:0,json:String::new(),pending_previews:BTreeMap::new(),frame:None,key:String::new(),wire:crate::updates::WireBase::default(),activity:Arc::default()}));
        let mut actor=Actor::new(shared,Arc::new(crate::updates::Updates::default()));
        actor.phase="connected".into();actor.selected_session="s".into();actor.selected_block="b".into();actor.navigation_id=2;
        let runtime=tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        for kind in ["take","resize"] {
            for (session,block,nav) in [("old","b",2),("s","old",2),("s","b",1)] {
                runtime.block_on(actor.command(json!({"type":kind,"session":session,"block":block,"navigationId":nav,"columns":40,"rows":30}))).unwrap();
                assert!(!actor.controlled);
            }
            actor.visible=false;
            runtime.block_on(actor.command(json!({"type":kind,"session":"s","block":"b","navigationId":2,"columns":40,"rows":30}))).unwrap();
            actor.visible=true;actor.paused=true;
            runtime.block_on(actor.command(json!({"type":kind,"session":"s","block":"b","navigationId":2,"columns":40,"rows":30}))).unwrap();
            actor.paused=false;
        }
    }

    #[test] fn server_progress_events_update_background_tabs_and_activity_and_clear_on_recovery() {
        let shared=Arc::new(Mutex::new(Shared{revision:0,json:String::new(),pending_previews:BTreeMap::new(),frame:None,key:String::new(),wire:crate::updates::WireBase::default(),activity:Arc::default()}));
        let mut actor=Actor::new(shared.clone(),Arc::new(crate::updates::Updates::default()));
        actor.selected_session="s".into();actor.windows=json!([{"blocks":[{"id":"a"},{"id":"b"}]}]);
        actor.activity_panes=json!([{"session":{"session_id":"s","label":"Session"},"window":{"window_id":"w","label":"Tab"},"block":{"block_id":"b","label":"cargo"}}]);
        let runtime=tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        let event=|state:&str,percent:Value|json!({"payload":{"type":"session_event","payload":{"session_id":"s","event":{"type":"block_event","payload":{"creator_name":"com.superlogical.terminal","block_id":"b","name":"progress_report","payload":{"state":state,"progress":percent}}}}}});
        runtime.block_on(actor.event(event("set",json!(40)))).unwrap();
        let state:Value=serde_json::from_str(&shared.lock().unwrap().json).unwrap();assert_eq!(state["terminalProgress"]["b"]["progress"],40);
        assert_eq!(shared.lock().unwrap().activity[0]["progressOnly"],true);
        runtime.block_on(actor.event(event("remove",Value::Null))).unwrap();assert!(actor.progress_reports.is_empty());assert_eq!(shared.lock().unwrap().activity.as_ref(),&json!([]));
        runtime.block_on(actor.event(event("indeterminate",Value::Null))).unwrap();actor.lose_connections();actor.publish();
        assert!(actor.progress_reports.is_empty());assert_eq!(shared.lock().unwrap().activity.as_ref(),&json!([]));
    }

    #[test] fn semantic_copy_is_local_and_uses_the_frozen_viewport_and_target() {
        let shared=Arc::new(Mutex::new(Shared{revision:0,json:String::new(),pending_previews:BTreeMap::new(),frame:None,key:String::new(),wire:crate::updates::WireBase::default(),activity:Arc::default()}));
        let mut actor=Actor::new(shared,Arc::new(crate::updates::Updates::default()));
        actor.visible=true;actor.paused=false;actor.phase="reconnecting".into();actor.selected_session="s".into();actor.selected_block="b".into();actor.connection_epoch=2;actor.navigation_id=3;
        let mut terminal=rex_core::ghostty::Terminal::new(30,4).unwrap();
        terminal.write(b"\x1b]133;A\x07$ \x1b]133;B\x07echo hello\x1b]133;C\x07\r\nhello\r\n\x1b]133;D;0\x07\x1b]133;A\x07$ \x1b]133;B\x07").unwrap();
        let mut replica=Replica::new(42);replica.feed(&terminal.encode().unwrap()).unwrap();
        let frame=replica.terminal().unwrap().frame().unwrap();actor.replica=Some(replica);
        let runtime=tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        let mut command=json!({"type":"copy_semantic","session":"s","block":"b","connectionEpoch":2,"navigationId":3,
            "columns":frame.columns,"rows":frame.rows,"scrollTotal":frame.scroll_total,"scrollOffset":frame.scroll_offset,"viewport":frame.text(),"col":0,"row":1,"output":false});
        runtime.block_on(actor.command(command.clone())).unwrap();assert_eq!(actor.clipboard["text"],"echo hello");assert_eq!(actor.clipboard["local"],true);
        command["output"]=json!(true);runtime.block_on(actor.command(command.clone())).unwrap();assert_eq!(actor.clipboard["text"],"hello");
        let clipboard=actor.clipboard.clone();
        let mut stale=command.clone();stale["block"]=json!("other");runtime.block_on(actor.command(stale)).unwrap();assert_eq!(actor.clipboard,clipboard);
        command["viewport"]=json!("changed");assert!(runtime.block_on(actor.command(command)).unwrap_err().to_string().contains("Select the text again"));
        assert_eq!(actor.clipboard,clipboard);assert!(actor.control.is_none());assert!(!actor.controlled);
    }

    #[test] fn prompt_navigation_is_local_and_rejects_stale_targets_while_disconnected() {
        let shared=Arc::new(Mutex::new(Shared{revision:0,json:String::new(),pending_previews:BTreeMap::new(),frame:None,key:String::new(),wire:crate::updates::WireBase::default(),activity:Arc::default()}));
        let mut actor=Actor::new(shared,Arc::new(crate::updates::Updates::default()));
        actor.visible=true;actor.paused=false;actor.phase="preview".into();actor.selected_session="s".into();actor.selected_block="b".into();actor.connection_epoch=2;actor.navigation_id=3;
        let mut terminal=rex_core::ghostty::Terminal::new(30,4).unwrap();
        for i in 0..8 {terminal.write(format!("\x1b]133;A\x07$ \x1b]133;B\x07command_{i}\x1b]133;C\x07\r\noutput\r\n\x1b]133;D;0\x07").as_bytes()).unwrap();}
        let bottom=terminal.frame().unwrap().scroll_offset;
        let mut replica=Replica::new(42);replica.feed(&terminal.encode().unwrap()).unwrap();actor.replica=Some(replica);
        let runtime=tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        let command=json!({"type":"scroll_prompt","session":"s","block":"b","connectionEpoch":2,"navigationId":3,"direction":-1});
        for (key,wrong) in [("session",json!("old")),("block",json!("old")),("connectionEpoch",json!(1)),("navigationId",json!(1))] {
            let mut stale=command.clone();stale[key]=wrong;runtime.block_on(actor.command(stale)).unwrap();
            assert_eq!(actor.replica.as_mut().unwrap().terminal().unwrap().frame().unwrap().scroll_offset,bottom);
        }
        runtime.block_on(actor.command(command)).unwrap();
        assert!(actor.replica.as_mut().unwrap().terminal().unwrap().frame().unwrap().scroll_offset<bottom);
        assert!(actor.control.is_none());assert!(!actor.controlled);
    }

    #[test] fn osc52_updates_publish_once_with_the_source_pane_and_epoch() {
        let shared=Arc::new(Mutex::new(Shared{revision:0,json:String::new(),pending_previews:BTreeMap::new(),frame:None,key:String::new(),wire:crate::updates::WireBase::default(),activity:Arc::default()}));
        let mut actor=Actor::new(shared.clone(),Arc::new(crate::updates::Updates::default()));
        actor.selected_block="pane-a".into();actor.connection_epoch=2;actor.navigation_id=3;
        let mut terminal=rex_core::ghostty::Terminal::new(30,6).unwrap();let mut replica=Replica::new(42);
        replica.feed(&terminal.encode().unwrap()).unwrap();replica.feed(b"\x1b]52;c;aGVsbG8=\x07").unwrap();
        actor.replica=Some(replica);actor.update_apcs();
        let published:Value=serde_json::from_str(&shared.lock().unwrap().json).unwrap();
        assert_eq!(published["clipboard"],json!({"serial":1,"text":"hello","block":"pane-a","connection":2,"navigation":3}));
        let revision=shared.lock().unwrap().revision;actor.update_apcs();assert_eq!(shared.lock().unwrap().revision,revision);
        actor.replica.as_mut().unwrap().feed(b"\x1b]52;c;dHdv\x07").unwrap();actor.update_apcs();
        assert_eq!(actor.clipboard["serial"],2);assert_eq!(actor.clipboard["text"],"two");
    }

    #[test] fn delayed_resize_apcs_cannot_revoke_canonical_ownership() {
        let shared=Arc::new(Mutex::new(Shared{revision:0,json:String::new(),pending_previews:BTreeMap::new(),frame:None,key:String::new(),wire:crate::updates::WireBase::default(),activity:Arc::default()}));
        let mut actor=Actor::new(shared,Arc::new(crate::updates::Updates::default()));
        actor.controlled=true;actor.replica=Some(Replica::new(1));
        actor.replica.as_mut().unwrap().events.push(json!({"type":"resize","owner":"previous-client"}));
        actor.update_apcs();assert!(actor.controlled);
        actor.controlled=false;actor.replica.as_mut().unwrap().events.push(json!({"type":"resize","owner":"late-own-claim"}));
        actor.update_apcs();assert!(!actor.controlled);
    }
    #[test] fn unowned_layout_resize_is_quiet_but_unowned_typing_is_rejected() {
        let shared=Arc::new(Mutex::new(Shared{revision:0,json:String::new(),pending_previews:BTreeMap::new(),frame:None,key:String::new(),wire:crate::updates::WireBase::default(),activity:Arc::default()}));
        let mut actor=Actor::new(shared,Arc::new(crate::updates::Updates::default()));
        let runtime=tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        runtime.block_on(actor.command(json!({"type":"resize","columns":40,"rows":30}))).unwrap();
        assert_eq!(runtime.block_on(actor.command(json!({"type":"input","text":"x"}))).unwrap_err().to_string(),"Take control before typing");
    }

    #[test] fn confirmed_ownership_clears_only_the_stale_ownership_error() {
        for text in ["Take control before typing","Take control before typing or resizing"] {
            let mut error=text.to_owned();clear_ownership_error(&mut error);assert!(error.is_empty());
        }
        let mut uncertain="Request outcome is uncertain; input was not resent".to_owned();
        clear_ownership_error(&mut uncertain);assert!(!uncertain.is_empty());
    }

    #[test] fn route_handoffs_preserve_live_connections_until_io_fails() {
        let shared=Arc::new(Mutex::new(Shared {revision:0,json:String::new(),activity:Arc::default(),pending_previews:BTreeMap::new(),frame:None,key:String::new(),wire:crate::updates::WireBase::default()}));
        let mut actor=Actor::new(shared,Arc::new(crate::updates::Updates::default()));
        actor.endpoint=Some(Endpoint::new("https://example.invalid",Duration::from_secs(10)).unwrap());
        actor.selected_session="s".into();actor.selected_block="b".into();actor.phase="connected".into();actor.controlled=true;actor.connection_epoch=7;
        let runtime=tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        for online in [false,true,true] {
            runtime.block_on(actor.command(json!({"type":"network","online":online,"force":true}))).unwrap();
            assert_eq!(actor.phase,"connected");assert!(actor.controlled);assert_eq!(actor.connection_epoch,7);assert!(actor.retry.is_none());
        }
        runtime.block_on(actor.command(json!({"type":"network","online":false}))).unwrap();
        actor.recover(anyhow::anyhow!("actual socket failure"));
        assert_eq!(actor.phase,"reconnecting");assert!(!actor.controlled);assert!(actor.retry.is_none());
        runtime.block_on(actor.command(json!({"type":"network","online":true}))).unwrap();
        assert!(actor.retry.is_some());assert_eq!(actor.selected_block,"b");
    }

    #[test] fn losing_network_cancels_a_pending_handshake_and_preserves_the_target() {
        let shared=Arc::new(Mutex::new(Shared {revision:0,json:String::new(),activity:Arc::default(),pending_previews:BTreeMap::new(),frame:None,key:String::new(),wire:crate::updates::WireBase::default()}));
        let mut actor=Actor::new(shared,Arc::new(crate::updates::Updates::default()));
        actor.endpoint=Some(Endpoint::new("https://example.invalid",Duration::from_secs(10)).unwrap());actor.selected_session="s".into();actor.selected_block="b".into();
        actor.connect_task=Some(Box::pin(pending()));actor.phase="connecting".into();
        let runtime=tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        runtime.block_on(actor.command(json!({"type":"network","online":false}))).unwrap();
        assert!(actor.connect_task.is_none());assert!(actor.retry.is_none());assert_eq!(actor.selected_block,"b");assert!(!actor.controlled);
        runtime.block_on(actor.command(json!({"type":"network","online":true}))).unwrap();assert!(actor.retry.is_some());
        actor.connect_task=Some(Box::pin(pending()));runtime.block_on(actor.command(json!({"type":"retry"}))).unwrap();assert!(actor.connect_task.is_none());assert!(actor.retry.is_some());
    }

    #[test] fn reordering_windows_preserves_program_metadata() {
        let shared=Arc::new(Mutex::new(Shared {revision:0,json:String::new(),activity:Arc::default(),pending_previews:BTreeMap::new(),frame:None,key:String::new(),wire:crate::updates::WireBase::default()}));
        let mut actor=Actor::new(shared,Arc::new(crate::updates::Updates::default()));
        let window=|id:&str|json!({"window_id":id,"label":id,"layers":[{"blocks":[{"block_id":id,"creator_name":"com.superlogical.terminal"}]}]});
        actor.set_view(&json!({"windows":[window("a"),window("b")]}));
        actor.selected_block="a".into();actor.programs=json!({"a":["codex"]});actor.titles=json!({"a":"Title"});actor.working=json!(["a"]);
        actor.set_view(&json!({"windows":[window("b"),window("a")]}));
        assert_eq!(actor.windows[0]["id"],"b");assert_eq!(actor.selected_block,"a");
        assert_eq!(actor.programs,json!({"a":["codex"]}));assert_eq!(actor.titles["a"],"Title");assert_eq!(actor.working,json!(["a"]));
    }

    #[test]
    #[ignore = "requires an explicitly supplied owned Rex server"]
    fn live_reorder_tabs_preserves_selection_control_and_size_and_syncs_other_clients() {
        let server=std::env::var("REX_TEST_SERVER").expect("set REX_TEST_SERVER");
        let runtime=tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        let mut writer=runtime.block_on(Control::connect(Endpoint::new(&server,Duration::from_secs(10)).unwrap(),None,Trace::default())).unwrap();
        let layout=json!({"block":{"flavor":"com.superlogical.terminal.shell","options":{"shell":"none","command":["/bin/sh","-c","printf REORDER_READY; cat"]}}});
        let created=runtime.block_on(writer.call("session.create",json!({"label":format!("rexlink-reorder-test-{}",std::process::id()),"initial_windows":[{"window_label":"One","layout":layout},{"window_label":"Two","layout":layout},{"window_label":"Three","layout":layout}]}))).unwrap();
        let id=created["session_id"].as_str().unwrap();
        let windows=created["initial_windows"].as_array().unwrap().iter().map(|w|w["window_id"].clone()).collect::<Vec<_>>();
        let block=&created["initial_windows"][0]["block_ids"][0];
        let result=std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            runtime.block_on(writer.attach(id)).unwrap();
            let app=Session::spawn().unwrap();app.command(json!({"type":"connect","server":server})).unwrap();wait(&app,|v|v["phase"]=="connected");
            app.command(json!({"type":"session","id":id,"block":block})).unwrap();wait(&app,|v|v["selectedBlock"]==*block && text(v).contains("REORDER_READY"));
            app.command(json!({"type":"take"})).unwrap();wait(&app,|v|v["controlled"]==true);
            app.command(json!({"type":"resize","columns":40,"rows":20})).unwrap();wait(&app,|v|v["screen"]["columns"]==40 && v["screen"]["rows"]==20);
            let ids=|v:&Value|v["windows"].as_array().unwrap().iter().map(|w|w["id"].clone()).collect::<Vec<_>>();
            for (request,tab,before,expected) in [
                (1,&windows[2],windows[0].clone(),vec![windows[2].clone(),windows[0].clone(),windows[1].clone()]),
                (2,&windows[0],json!(""),vec![windows[2].clone(),windows[1].clone(),windows[0].clone()]),
            ] {
                app.command(json!({"type":"move_tab","session":id,"id":tab,"before":before,"requestId":request})).unwrap();
                let moved=wait(&app,|v|v["tabMoveId"]==request);
                assert_eq!(moved["error"],"");assert_eq!(ids(&moved),expected);assert_eq!(moved["selectedBlock"],*block);assert_eq!(moved["controlled"],true);
                assert_eq!(moved["screen"]["columns"],40);assert_eq!(moved["screen"]["rows"],20);
                let view=runtime.block_on(writer.call("session.view",json!({"session_id":id}))).unwrap();
                assert_eq!(view["windows"].as_array().unwrap().iter().map(|w|w["window_id"].clone()).collect::<Vec<_>>(),expected);
            }
            runtime.block_on(writer.call("session.move_window",json!({"session_id":id,"window_id":windows[0],"before_window_id":windows[2]}))).unwrap();
            wait(&app,|v|v["windows"][0]["id"]==windows[0]);
            for (request,session,tab,before) in [(3,"stale",windows[0].clone(),json!("")),(4,id,json!("missing"),json!("")),(5,id,windows[0].clone(),windows[0].clone())] {
                app.command(json!({"type":"move_tab","session":session,"id":tab,"before":before,"requestId":request})).unwrap();
                let rejected=wait(&app,|v|v["tabMoveId"]==request);assert_ne!(rejected["error"],"");assert_eq!(rejected["selectedBlock"],*block);assert_eq!(rejected["controlled"],true);
                assert_eq!(ids(&rejected),vec![windows[0].clone(),windows[2].clone(),windows[1].clone()]);
            }
        }));
        runtime.block_on(writer.call("session.destroy",json!({"session_id":id}))).unwrap();runtime.block_on(writer.close()).unwrap();result.unwrap();
    }

    #[test]
    #[ignore = "requires an explicitly supplied owned Rex server"]
    fn live_rename_session_preserves_active_pane_and_syncs_external_labels() {
        let server=std::env::var("REX_TEST_SERVER").expect("set REX_TEST_SERVER");
        let runtime=tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        let mut writer=runtime.block_on(Control::connect(Endpoint::new(&server,Duration::from_secs(10)).unwrap(),None,Trace::default())).unwrap();
        let mut created=Vec::new();
        let result=std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            for index in 0..2 {
                created.push(runtime.block_on(writer.call("session.create",json!({"label":format!("rexlink-rename-test-{}-{index}",std::process::id()),"initial_windows":[{"layout":{"block":{"flavor":"com.superlogical.terminal.shell","options":{"shell":"none","command":["/bin/sh","-c","printf RENAME_READY; cat"]}}}}]}))).unwrap());
            }
            let app=Session::spawn().unwrap();app.command(json!({"type":"connect","server":server})).unwrap();wait(&app,|v|v["phase"]=="connected");
            let first=&created[0]["session_id"];let second=&created[1]["session_id"];let block=&created[0]["initial_windows"][0]["block_ids"][0];
            app.command(json!({"type":"session","id":first,"block":block})).unwrap();wait(&app,|v|v["selectedBlock"]==*block && text(v).contains("RENAME_READY"));
            app.command(json!({"type":"take"})).unwrap();wait(&app,|v|v["controlled"]==true);
            app.command(json!({"type":"resize","columns":40,"rows":20})).unwrap();let before=wait(&app,|v|v["screen"]["columns"]==40 && v["screen"]["rows"]==20);
            app.command(json!({"type":"rename_session","id":second,"label":"  Renamed λ 🦀  "})).unwrap();
            let renamed=wait(&app,|v|v["creating"]==false && v["sessions"].as_array().is_some_and(|items|items.iter().any(|s|s["session_id"]==*second && s["label"]=="Renamed λ 🦀")));
            assert_eq!(renamed["selectedSession"],*first);assert_eq!(renamed["selectedBlock"],*block);assert_eq!(renamed["controlled"],true);
            assert_eq!(renamed["screen"]["columns"],before["screen"]["columns"]);assert_eq!(renamed["screen"]["rows"],before["screen"]["rows"]);
            runtime.block_on(writer.attach(second.as_str().unwrap())).unwrap();
            runtime.block_on(writer.call("session.set_label",json!({"session_id":second,"label":"Changed in another client"}))).unwrap();
            wait(&app,|v|v["sessions"].as_array().is_some_and(|items|items.iter().any(|s|s["session_id"]==*second && s["label"]=="Changed in another client")));
            app.command(json!({"type":"rename_session","id":first,"label":"Active renamed"})).unwrap();
            let active=wait(&app,|v|v["creating"]==false && v["sessions"].as_array().is_some_and(|items|items.iter().any(|s|s["session_id"]==*first && s["label"]=="Active renamed")));
            assert_eq!(active["controlled"],true);assert_eq!(active["screen"]["columns"],40);
            app.command(json!({"type":"rename_session","id":second,"label":"bad\nname"})).unwrap();let invalid=wait(&app,|v|v["error"].as_str().is_some_and(|e|e.contains("control characters")));assert_eq!(invalid["controlled"],true);
            runtime.block_on(writer.call("session.destroy",json!({"session_id":second}))).unwrap();
            app.command(json!({"type":"rename_session","id":second,"label":"Missing"})).unwrap();
            let missing=wait(&app,|v|v["creating"]==false && v["error"].as_str().is_some_and(|e|e.contains("session_not_found")));assert_eq!(missing["controlled"],true);
        }));
        let sessions=runtime.block_on(writer.call("session.list",json!({}))).unwrap();
        for value in created {
            if sessions["sessions"].as_array().unwrap().iter().any(|s|s["session_id"]==value["session_id"]) {
                runtime.block_on(writer.attach(value["session_id"].as_str().unwrap())).unwrap();runtime.block_on(writer.call("session.destroy",json!({"session_id":value["session_id"]}))).unwrap();
            }
        }
        runtime.block_on(writer.close()).unwrap();result.unwrap();
    }

    #[test]
    #[ignore = "requires an explicitly supplied owned Rex server"]
    fn live_terminal_edits_rename_float_and_dock_without_changing_the_viewed_pane() {
        let server=std::env::var("REX_TEST_SERVER").expect("set REX_TEST_SERVER");
        let runtime=tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        let mut writer=runtime.block_on(Control::connect(Endpoint::new(&server,Duration::from_secs(10)).unwrap(),None,Trace::default())).unwrap();
        let block=json!({"block":{"flavor":"com.superlogical.terminal.shell","options":{"shell":"none","command":["/bin/sh","-c","printf EDIT_READY; cat"]}}});
        let created=runtime.block_on(writer.call("session.create",json!({"label":"rexling-pane-edit-test","initial_windows":[{"window_label":"Original","layout":{"split":{"direction":"horizontal","before":block,"after":block}}}]}))).unwrap();
        let id=created["session_id"].as_str().unwrap();let window=&created["initial_windows"][0]["window_id"];let first=&created["initial_windows"][0]["block_ids"][0];let second=&created["initial_windows"][0]["block_ids"][1];
        let result=std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            runtime.block_on(writer.attach(id)).unwrap();
            let app=Session::spawn().unwrap();app.command(json!({"type":"connect","server":server})).unwrap();wait(&app,|v|v["phase"]=="connected");
            app.command(json!({"type":"session","id":id,"block":first})).unwrap();wait(&app,|v|v["selectedBlock"]==*first && text(v).contains("EDIT_READY"));
            app.command(json!({"type":"take"})).unwrap();wait(&app,|v|v["controlled"]==true);
            app.command(json!({"type":"rename_tab","session":id,"window":window,"id":window,"label":"Renamed tab"})).unwrap();
            wait(&app,|v|v["windows"][0]["label"]=="Renamed tab");
            app.command(json!({"type":"rename_pane","session":id,"window":window,"id":second,"label":"Build logs"})).unwrap();
            wait(&app,|v|v["windows"][0]["blocks"].as_array().unwrap().iter().any(|b|b["id"]==*second && b["label"]=="Build logs"));
            for (kind,floating) in [("float_pane",true),("dock_pane",false)] {
                app.command(json!({"type":kind,"session":id,"window":window,"id":second,"anchor":first,"direction":"below"})).unwrap();
                let changed=wait(&app,|v|v["windows"][0]["layout"].as_array().unwrap().iter().any(|p|p["id"]==*second && p["floating"]==floating));
                assert_eq!(changed["error"],"");assert_eq!(changed["selectedBlock"],*first);assert_eq!(changed["controlled"],true);
            }
            let view=runtime.block_on(writer.call("session.view",json!({"session_id":id}))).unwrap();
            assert_eq!(view["windows"][0]["label"],"Renamed tab");assert_eq!(view["windows"][0]["layers"].as_array().unwrap().len(),1);

            let mut tiled=json!([first,second]);
            for (position,axis,before) in [("left",0,true),("above",1,true),("right",0,false),("below",1,false)] {
                app.command(json!({"type":"place_pane","session":id,"window":window,"id":second,"anchor":first,
                    "position":position,"tiled":tiled})).unwrap();
                let placed=wait(&app,|v| {
                    let Some(layout)=v["windows"][0]["layout"].as_array() else {return false;};
                    let origin=|id:&Value|layout.iter().find(|p|p["id"]==*id).and_then(|p|p["rect"][axis].as_f64());
                    match (origin(first),origin(second)) {
                        (Some(anchor),Some(moved))=>if before {moved<anchor} else {moved>anchor},
                        _=>false,
                    }
                });
                let layout=placed["windows"][0]["layout"].as_array().unwrap();
                assert_eq!(layout.len(),2);
                assert!(layout.iter().all(|p|p["floating"]==false));
                assert_eq!(placed["error"],"");assert_eq!(placed["selectedBlock"],*first);assert_eq!(placed["controlled"],true);
                tiled=Value::Array(layout.iter().map(|p|p["id"].clone()).collect());
            }

            app.command(json!({"type":"float_pane","session":id,"window":window,"id":second,"focus":true})).unwrap();
            let floated=wait(&app,|v|v["selectedBlock"]==*second && v["windows"][0]["layout"].as_array().unwrap().iter()
                .any(|p|p["id"]==*second && p["floating"]==true));
            let layer=floated["windows"][0]["layout"].as_array().unwrap().iter().find(|p|p["id"]==*second).unwrap()["layerId"].clone();
            assert!(layer.as_str().is_some_and(|id|!id.is_empty()));
            let bounds=json!([0.2,0.25,0.55,0.5]);
            app.command(json!({"type":"resize_floating","session":id,"window":window,"id":second,"layerId":layer,"bounds":bounds})).unwrap();
            wait(&app,|v|v["windows"][0]["layout"].as_array().unwrap().iter().any(|p|p["id"]==*second && p["layerBounds"]==bounds));
            app.command(json!({"type":"new_pane","session":id,"anchor":second,"direction":"horizontal","keepTiled":true})).unwrap();
            let split=wait(&app,|v|v["selectedBlock"]!=*second && !v["screen"].is_null() &&
                v["windows"][0]["layout"].as_array().unwrap().iter().filter(|p|p["layerId"]==layer).count()==2);
            assert_eq!(split["error"],"");
            let new_block=&split["selectedBlock"];
            app.command(json!({"type":"mirror","session":id,"window":window,"panes":[
                {"block":first,"columns":40,"rows":20},{"block":second,"columns":40,"rows":20}
            ]})).unwrap();
            wait(&app,|v|!v["previews"][second.as_str().unwrap()].is_null() && v["error"]=="");
            app.command(json!({"type":"zoom","session":id,"block":new_block,"zoom":true})).unwrap();
            wait(&app,|v|v["windows"][0]["zoomed"]==*new_block);
            app.command(json!({"type":"minimize_floating","session":id,"window":window,"id":new_block,"layerId":layer})).unwrap();
            let layer_minimized=|v:&Value| v["windows"][0]["layout"].as_array().is_some_and(|layout|
                layout.iter().filter(|pane|pane["layerId"]==layer && pane["minimized"]==true).count()==2);
            let minimized=wait(&app,|v|layer_minimized(v) && v["selectedBlock"]==*first && !v["screen"].is_null());
            assert_eq!(minimized["windows"][0]["zoomed"],"");
            let until=std::time::Instant::now()+Duration::from_secs(5);
            loop {
                let view=runtime.block_on(writer.call("session.view",json!({"session_id":id}))).unwrap();
                let hidden=&view["windows"][0]["layers"][1];
                assert_eq!(hidden["state"],"minimized");
                if hidden["blocks"].as_array().unwrap().iter().all(|pane|pane["owner"].is_null()) { break; }
                assert!(std::time::Instant::now()<until,"Minimized layer kept a mirror owner: {hidden}");
                std::thread::sleep(Duration::from_millis(20));
            }

            // Restoring workspace history does not reopen another client's minimized layer.
            app.command(json!({"type":"block","id":second,"navigationId":10})).unwrap();
            wait(&app,|v|v["navigationId"]==10 && layer_minimized(v) && v["selectedBlock"]==*first);
            app.command(json!({"type":"block","id":second,"navigationId":11,"restoreMinimized":true,"keepTiled":true})).unwrap();
            wait(&app,|v|!layer_minimized(v) && v["selectedBlock"]==*second && text(v).contains("EDIT_READY"));
            let view=runtime.block_on(writer.call("session.view",json!({"session_id":id}))).unwrap();
            assert!(view["windows"][0]["layers"][1]["state"].is_null());

            for state in ["minimized","normal"] {
                runtime.block_on(writer.call("session.set_layer_state",json!({"session_id":id,"layer_id":layer,"state":state}))).unwrap();
                wait(&app,|v|layer_minimized(v)==(state=="minimized") && v["selectedBlock"]==*first);
            }
            app.command(json!({"type":"block","id":second,"navigationId":12,"restoreMinimized":true})).unwrap();
            wait(&app,|v|v["navigationId"]==12 && v["selectedBlock"]==*second && !v["screen"].is_null());
            app.command(json!({"type":"pause"})).unwrap();
            wait(&app,|v|v["phase"]=="paused");
            runtime.block_on(writer.call("session.set_layer_state",json!({"session_id":id,"layer_id":layer,"state":"minimized"}))).unwrap();
            app.command(json!({"type":"resume"})).unwrap();
            wait(&app,|v|v["phase"]=="connected" && v["selectedBlock"]==*first && layer_minimized(v) && !v["screen"].is_null());
        }));
        runtime.block_on(writer.call("session.destroy",json!({"session_id":id}))).unwrap();runtime.block_on(writer.close()).unwrap();result.unwrap();
    }

}

#[cfg(test)]
#[path="latency_tests.rs"]
mod latency_tests;
#[cfg(test)]
#[path="pane_reorder_tests.rs"]
mod pane_reorder_tests;

#[cfg(test)]
#[path="reconnect_tests.rs"]
mod reconnect_tests;
