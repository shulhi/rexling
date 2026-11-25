//! Live data streams for the other visible panes, sharing the focused pane's control connection.
use anyhow::Result;
use rex_core::{
    control::{Control, Trace},
    data::Data,
    ghostty::{Frame, Replica, Terminal},
    transport::{Endpoint, TERMINAL},
};
use serde_json::{Value, json};
use std::{
    future::{Future, poll_fn},
    pin::Pin,
    sync::Arc,
    task::Poll,
    time::Duration,
};
use tokio::time::Instant;

// Rex's default window limit. The unfolded workspace can display a split floating layer
// together with its tiled panes.
pub const MAX_PANES: usize = 64;
const FRAME_INTERVAL: Duration = Duration::from_millis(33);
const BUSY_FRAME_INTERVAL: Duration = Duration::from_millis(66);
const OBSCURED_FRAME_INTERVAL: Duration = Duration::from_millis(200);

#[derive(Clone, Debug, PartialEq)]
pub struct Wanted {
    pub block: String,
    pub columns: u16,
    pub rows: u16,
}

pub enum Step {
    Owned,
    Ticket(Value),
    Open(Data),
    Sized((u16, u16)),
}

type Job = Pin<Box<dyn Future<Output = Result<Step>>>>;

struct Live {
    data: Data,
    replica: Replica,
    dirty: bool,
    animation: Option<Instant>,
}

struct Slot {
    want: Wanted,
    live: Option<Live>,
    // Set as soon as an ownership request is sent so cancellation also releases it.
    claimed: bool,
    presented: bool,
    owned: bool,
    size: Option<(u16, u16)>,
    opening: bool,
    job: Option<Job>,
    retry_at: Instant,
    backoff: u64,
}

impl Slot {
    fn new(want: Wanted) -> Self {
        Self {
            want,
            live: None,
            claimed: false,
            presented: false,
            owned: false,
            size: None,
            opening: false,
            job: None,
            retry_at: Instant::now(),
            backoff: 1,
        }
    }

    fn failed(&mut self) {
        if let Some(live) = self.live.take() {
            close(live.data);
        }
        self.presented = false;
        self.owned = false;
        self.size = None;
        self.retry_at = Instant::now() + Duration::from_secs(self.backoff);
        self.backoff = (self.backoff * 2).min(15);
    }
}

#[derive(Default)]
pub struct Mirror {
    session: String,
    slots: Vec<Slot>,
    release: Vec<(String, String)>,
    drawn_at: Option<Instant>,
    next_slot: usize,
    obscured: bool,
}

pub enum Event {
    Job(usize, Result<Step>),
    Data(usize, Result<Vec<u8>>),
}

/// Parses Android's `mirror` command: `{session, panes: [{block, columns, rows}]}`.
pub fn parse(value: &Value) -> (String, Vec<Wanted>) {
    let session = value["session"].as_str().unwrap_or("").to_owned();
    let mut wanted: Vec<Wanted> = Vec::new();
    for pane in value["panes"].as_array().into_iter().flatten() {
        let Some(block) = pane["block"].as_str().filter(|b| !b.is_empty()) else {
            continue;
        };
        if wanted.iter().any(|w| w.block == block) {
            continue;
        }
        let size = |key: &str| pane[key].as_u64().unwrap_or(0).clamp(1, 512) as u16;
        wanted.push(Wanted {
            block: block.to_owned(),
            columns: size("columns"),
            rows: size("rows").min(256),
        });
        if wanted.len() == MAX_PANES {
            break;
        }
    }
    (session, wanted)
}

impl Mirror {
    pub fn set_obscured(&mut self, obscured: bool) {
        if self.obscured && !obscured {
            // Let the latest dirty frame through as soon as the drawer closes.
            self.drawn_at = None;
        }
        self.obscured = obscured;
    }

    pub fn wants(&self, session: &str, block: &str) -> bool {
        self.session == session && self.slots.iter().any(|slot| slot.want.block == block)
    }

    fn ready_pane(&mut self, block: &str) -> Option<&mut Live> {
        self.slots.iter_mut().find(|slot| slot.want.block == block)?
            .live.as_mut().filter(|live| live.replica.ready())
    }

    pub fn scroll(&mut self, block: &str, lines: Option<i32>) -> Result<()> {
        if let Some(live) = self.ready_pane(block) {
            let terminal = live.replica.terminal()?;
            match lines {
                Some(lines) => terminal.scroll_viewport(lines),
                None => terminal.scroll_to_bottom(),
            }
            live.dirty = true;
        }
        Ok(())
    }

    pub fn owned_terminal(&mut self, block: &str) -> Result<Option<&mut Terminal>> {
        self.slots.iter_mut().find(|slot| slot.want.block == block && slot.owned)
            .and_then(|slot| slot.live.as_mut())
            .filter(|live| live.replica.ready())
            .map(|live| live.replica.terminal())
            .transpose()
    }

    /// A pending ticket can be dropped. Once the data handshake starts, finish it so
    /// the focused pane does not race another HELLO for the same client and pane.
    pub async fn take(&mut self, block: &str) -> Option<(Data, Replica)> {
        let index = self
            .slots
            .iter()
            .position(|slot| slot.want.block == block)?;
        let slot = self.slots.remove(index);
        if let Some(live) = slot.live {
            return Some((live.data, live.replica));
        }
        if slot.opening
            && let Ok(Step::Open(data)) = slot.job?.await
        {
            let replica = Replica::new(data.nonce);
            return Some((data, replica));
        }
        None
    }

    pub fn adopt(&mut self, session: &str, block: String, data: Data, replica: Replica, owned: bool) {
        if self.session != session {
            self.request(session, Vec::new());
        }
        let mut slot = Slot::new(Wanted {
            block,
            columns: 0,
            rows: 0,
        });
        slot.owned = owned;
        slot.claimed = owned;
        slot.presented = replica.ready();
        slot.live = Some(Live {
            data,
            replica,
            dirty: true,
            animation: None,
        });
        self.slots.push(slot);
    }

    fn remove(&mut self, index: usize) {
        let slot = self.slots.remove(index);
        if slot.claimed {
            self.release.push((self.session.clone(), slot.want.block));
        }
        if let Some(live) = slot.live {
            close(live.data);
        }
        // Dropping an unfinished handshake also closes its socket. A later open
        // can retry if the server is still tearing that socket down.
    }

    pub fn request(&mut self, session: &str, wanted: Vec<Wanted>) {
        for index in (0..self.slots.len()).rev() {
            if self.session != session
                || !wanted
                    .iter()
                    .any(|want| want.block == self.slots[index].want.block)
            {
                self.remove(index);
            }
        }
        self.session = session.to_owned();
        for want in wanted {
            if let Some(slot) = self
                .slots
                .iter_mut()
                .find(|slot| slot.want.block == want.block)
            {
                slot.want = want;
            } else {
                self.slots.push(Slot::new(want));
            }
        }
    }

    pub fn retain(&mut self, allowed: impl Fn(&str) -> bool) {
        for index in (0..self.slots.len()).rev() {
            if !allowed(&self.slots[index].want.block) {
                self.remove(index);
            }
        }
    }

    /// Stop streaming a tab while leaving its panes at their current size.
    pub fn park(&mut self) -> Vec<String> {
        self.slots.drain(..).filter_map(|slot| {
            if let Some(live) = slot.live {
                close(live.data);
            }
            slot.claimed.then_some(slot.want.block)
        }).collect()
    }

    /// Send requests and return immediately to the actor loop. The control reader
    /// resolves their replies while input, rendering and other panes keep running.
    pub async fn start(&mut self, control: &mut Control) {
        for (session, block) in std::mem::take(&mut self.release) {
            let _ = control
                .start_call(
                    "session.disown_block",
                    json!({"session_id":session,"block_ids":[block]}),
                )
                .await;
        }
        for slot in &mut self.slots {
            if slot.job.is_some() || slot.want.columns == 0 || slot.retry_at > Instant::now() {
                continue;
            }
            let block = &slot.want.block;
            let session = &self.session;
            let size = (slot.want.columns, slot.want.rows);
            let job: Result<Job> = if !slot.owned {
                control
                    .start_call(
                        "session.own_block",
                        json!({"session_id":session,"block_ids":[block],"mode":"take"}),
                    )
                    .await
                    .map(|reply| {
                        slot.claimed = true;
                        Box::pin(async move { reply.receive().await.map(|_| Step::Owned) }) as Job
                    })
            } else if slot.live.is_none() {
                control.start_call("data.connect",
                    json!({"session_id":session,"block_id":block,"data_ref_id":"terminal","subprotocol":TERMINAL})).await.map(|reply| {
                        Box::pin(async move { reply.receive().await.map(Step::Ticket) }) as Job
                    })
            } else if slot.size != Some(size) {
                control.start_call("com.superlogical.terminal.resize",
                    json!({"session_id":session,"block_id":block,"args":{"columns":size.0,"rows":size.1}})).await.map(|reply| {
                        Box::pin(async move { reply.receive().await.map(|_| Step::Sized(size)) }) as Job
                    })
            } else {
                continue;
            };
            match job {
                Ok(job) => slot.job = Some(job),
                Err(_) => slot.failed(),
            }
        }
    }

    pub async fn next(&mut self) -> Event {
        poll_fn(|context| {
            let count = self.slots.len();
            for offset in 0..count {
                let index = (offset + self.next_slot) % count;
                let slot = &mut self.slots[index];
                if let Some(job) = slot.job.as_mut()
                    && let Poll::Ready(result) = job.as_mut().poll(context)
                {
                    self.next_slot = (index + 1) % count;
                    return Poll::Ready(Event::Job(index, result));
                }
                if let Some(live) = slot.live.as_mut()
                    && let Poll::Ready(result) = std::pin::pin!(live.data.read()).poll(context)
                {
                    self.next_slot = (index + 1) % count;
                    return Poll::Ready(Event::Data(index, result));
                }
            }
            Poll::Pending
        })
        .await
    }

    pub fn handle(&mut self, event: Event, endpoint: &Endpoint) {
        match event {
            Event::Job(index, result) => {
                let slot = &mut self.slots[index];
                slot.job = None;
                slot.opening = false;
                match result {
                    Ok(Step::Owned) => slot.owned = true,
                    Ok(Step::Ticket(ticket)) => {
                        let endpoint = endpoint.clone();
                        slot.opening = true;
                        slot.job = Some(Box::pin(async move {
                            Data::open_ticket(&endpoint, ticket, Trace::default())
                                .await
                                .map(Step::Open)
                        }));
                    }
                    Ok(Step::Open(data)) => {
                        let replica = Replica::new(data.nonce);
                        slot.live = Some(Live {
                            data,
                            replica,
                            dirty: false,
                            animation: None,
                        });
                    }
                    Ok(Step::Sized(size)) => slot.size = Some(size),
                    Err(_) => slot.failed(),
                }
            }
            Event::Data(index, result) => {
                let slot = &mut self.slots[index];
                let live = slot
                    .live
                    .as_mut()
                    .expect("mirror read without a live stream");
                if result.and_then(|bytes| live.replica.feed(&bytes)).is_err() {
                    slot.job = None;
                    slot.failed();
                } else {
                    live.replica.events.clear();
                    live.replica.clipboard_write = None;
                    live.dirty = true;
                }
            }
        }
    }

    pub fn waiting(&self) -> impl Iterator<Item = &str> {
        self.slots
            .iter()
            .filter(|slot| !slot.presented)
            .map(|slot| slot.want.block.as_str())
    }

    pub fn retry_due(&self) -> Option<Instant> {
        let now = Instant::now();
        self.slots
            .iter()
            .map(|slot| slot.retry_at)
            .filter(|at| *at > now)
            .min()
    }

    pub fn due(&self) -> Option<Instant> {
        let now = Instant::now();
        // Keep consuming streams and applying terminal updates at their usual pace.
        let interval = if self.obscured {
            OBSCURED_FRAME_INTERVAL
        } else if self.slots.len() >= 5 && self.slots.iter().filter(|slot| {
            slot.live.as_ref().is_some_and(|live| live.replica.ready() &&
                (live.dirty || live.animation.is_some_and(|at| at <= now)))
        }).nth(4).is_some() {
            // Leave actor time for focused input when several full frames are due together.
            BUSY_FRAME_INTERVAL
        } else {
            FRAME_INTERVAL
        };
        let next_frame = self.drawn_at.map_or(now, |at| at + interval);
        self.slots
            .iter()
            .filter_map(|slot| {
                let live = slot.live.as_ref().filter(|live| live.replica.ready())?;
                if live.dirty {
                    Some(next_frame)
                } else {
                    live.animation.map(|at| at.max(next_frame))
                }
            })
            .min()
    }

    pub fn frames(&mut self) -> Vec<(String, Arc<Frame>)> {
        let now = Instant::now();
        if self.due().is_none_or(|at| at > now) {
            return Vec::new();
        }
        self.drawn_at = Some(now);
        let mut frames = Vec::new();
        for slot in &mut self.slots {
            let Some(live) = slot.live.as_mut().filter(|live| {
                live.replica.ready() && (live.dirty || live.animation.is_some_and(|at| at <= now))
            }) else {
                continue;
            };
            live.dirty = false;
            live.animation = None;
            let frame = live.replica.terminal().and_then(|terminal| {
                if terminal.render_ready()? {
                    terminal.frame().map(Some)
                } else {
                    Ok(None)
                }
            });
            match frame {
                Ok(Some(frame)) => {
                    live.animation = frame
                        .next_image_delay_ms
                        .map(|ms| now + Duration::from_millis(ms));
                    slot.presented = true;
                    slot.backoff = 1;
                    frames.push((slot.want.block.clone(), Arc::new(frame)));
                }
                Ok(None) => live.dirty = true,
                Err(_) => {
                    slot.job = None;
                    slot.failed();
                }
            }
        }
        frames
    }

    /// Drop broken connections while preserving the requested layout for reconnect.
    pub fn clear(&mut self) {
        for slot in &mut self.slots {
            *slot = Slot::new(slot.want.clone());
        }
        self.release.clear();
        self.drawn_at = None;
    }
}

fn close(mut data: Data) {
    tokio::spawn(async move {
        let _ = tokio::time::timeout(Duration::from_secs(2), data.close()).await;
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_keeps_order_drops_duplicates_and_caps_panes() {
        let mut panes=json!([
            {"block": "b1", "columns": 80, "rows": 24},
            {"block": "b1", "columns": 10, "rows": 10},
            {"block": "", "columns": 10, "rows": 10},
            {"block": "b2", "columns": 9999, "rows": 9999},
            {"block": "b3", "columns": 0, "rows": 0},
            {"block": "b4", "columns": 40, "rows": 12},
            {"block": "b5", "columns": 40, "rows": 12},
        ]);
        panes.as_array_mut().unwrap().extend((6..=MAX_PANES+1).map(|i|json!({"block":format!("b{i}"),"columns":40,"rows":12})));
        let (session, wanted) = parse(&json!({"session":"session:a","panes":panes}));
        assert_eq!(session, "session:a");
        assert_eq!(wanted.len(),MAX_PANES);
        assert_eq!(
            &wanted[..4],
            vec![
                Wanted {
                    block: "b1".into(),
                    columns: 80,
                    rows: 24
                },
                Wanted {
                    block: "b2".into(),
                    columns: 512,
                    rows: 256
                },
                Wanted {
                    block: "b3".into(),
                    columns: 1,
                    rows: 1
                },
                Wanted {
                    block: "b4".into(),
                    columns: 40,
                    rows: 12
                },
            ]
        );
    }

    #[test]
    fn empty_mirror_has_nothing_due_and_no_frames() {
        let mut mirror = Mirror::default();
        assert!(mirror.due().is_none());
        assert!(mirror.frames().is_empty());
        assert!(mirror.slots.is_empty());
    }

    #[test]
    fn parking_a_tab_keeps_claimed_panes_without_queuing_disowns() {
        let mut mirror = Mirror::default();
        mirror.request("session", vec![
            Wanted { block: "claimed".into(), columns: 80, rows: 24 },
            Wanted { block: "pending".into(), columns: 80, rows: 24 },
        ]);
        mirror.slots[0].claimed = true;
        assert_eq!(mirror.park(), ["claimed"]);
        assert!(mirror.slots.is_empty());
        assert!(mirror.release.is_empty());
    }
}
