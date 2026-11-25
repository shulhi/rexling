//! Bounded, ordered local work; accepted input is bound to one activation.
use anyhow::{Result, ensure};
use serde_json::{Value, json};
use std::collections::VecDeque;

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct Target {
    pub session: String,
    pub block: String,
    pub navigation: u64,
    pub connection: u64,
}
impl Target {
    pub fn bind(&self, value: &mut Value) {
        for (key, field) in [
            ("session", json!(self.session)),
            ("block", json!(self.block)),
            ("navigationId", json!(self.navigation)),
            ("connectionEpoch", json!(self.connection)),
        ] {
            if value.get(key).is_none() {
                value[key] = field;
            }
        }
    }
    pub fn matches(&self, value: &Value) -> bool {
        value["session"] == self.session
            && value["block"] == self.block
            && value["navigationId"] == self.navigation
            && value["connectionEpoch"] == self.connection
    }
}
pub(crate) fn is_input(value: &Value) -> bool {
    matches!(value["type"].as_str(), Some("input" | "paste" | "click" | "wheel"))
}
const WHEEL_IN_FLIGHT: usize = 2;
const WHEEL_LINE_LIMIT: i64 = 32;

pub(crate) fn wheel_lines(value: &Value) -> i64 {
    value["lines"].as_i64().unwrap_or(0).clamp(-WHEEL_LINE_LIMIT, WHEEL_LINE_LIMIT)
}
fn plain(value: &Value) -> bool {
    value["type"] == "input"
        && value["text"]
            .as_str()
            .is_some_and(|s| !s.chars().any(char::is_control))
}
#[derive(Default)]
pub(crate) struct Commands {
    items: VecDeque<Value>,
    bytes: usize,
}
impl Commands {
    pub fn push(&mut self, mut value: Value, target: &Target) -> Result<()> {
        if is_input(&value) {
            target.bind(&mut value);
        }
        if value["type"] == "wheel" {
            let lines = wheel_lines(&value);
            if lines == 0 { return Ok(()); }
            if let Some(queued) = self.items.back_mut()
                && queued["type"] == "wheel"
                && wheel_lines(queued).signum() == lines.signum()
                && ["session", "block", "navigationId", "connectionEpoch", "mirror", "window", "col", "row", "cw", "ch", "columns", "rows"]
                    .iter().all(|key| queued[key] == value[key])
            {
                // Keep at most one batch of unsent motion for a continuous swipe.
                queued["lines"] = json!((wheel_lines(queued) + lines).clamp(-WHEEL_LINE_LIMIT, WHEEL_LINE_LIMIT));
                return Ok(());
            }
        }
        let bytes = value["text"].as_str().map_or(0, str::len);
        ensure!(
            self.items.len() < 128 && self.bytes.saturating_add(bytes) <= 1_048_576,
            "Input queue is full; queued input was not resent"
        );
        self.bytes += bytes;
        self.items.push_back(value);
        Ok(())
    }
    pub fn ready(&self, pending: usize, limit: usize) -> bool {
        self.items.front().is_some_and(|v| {
            match v["type"].as_str() {
                Some("input") => pending < limit,
                Some("wheel") => pending < limit.min(WHEEL_IN_FLIGHT),
                _ => pending == 0,
            }
        })
    }
    pub fn pop(&mut self, batch: bool) -> Option<Value> {
        let mut value = self.items.pop_front()?;
        self.bytes -= value["text"].as_str().map_or(0, str::len);
        if batch && plain(&value) {
            let mut text = value["text"].as_str().unwrap().to_owned();
            while let Some(next) = self.items.front() {
                if !plain(next)
                    || text.len() + next["text"].as_str().unwrap().len() > 4096
                    || ["session", "block", "navigationId", "connectionEpoch"]
                        .iter()
                        .any(|key| value[key] != next[key])
                {
                    break;
                }
                let next = self.items.pop_front().unwrap();
                let part = next["text"].as_str().unwrap();
                self.bytes -= part.len();
                text.push_str(part);
            }
            value["text"] = json!(text);
        }
        Some(value)
    }
    pub fn clear(&mut self) {
        self.items.clear();
        self.bytes = 0;
    }
    pub fn discard_input(&mut self) {
        self.items.retain(|v| !is_input(v));
        self.bytes = self
            .items
            .iter()
            .map(|v| v["text"].as_str().map_or(0, str::len))
            .sum();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn target(block: &str) -> Target {
        Target {
            session: "s".into(),
            block: block.into(),
            navigation: 2,
            connection: 3,
        }
    }
    #[test]
    fn batches_only_adjacent_text_and_keeps_keys_pastes_and_control_barriers() {
        let mut queue = Commands::default();
        let t = target("a");
        for v in [
            json!({"type":"input","text":"λ"}),
            json!({"type":"input","text":"🦀"}),
            json!({"type":"input","text":"\r"}),
            json!({"type":"paste","text":"pasted"}),
            json!({"type":"release"}),
        ] {
            queue.push(v, &t).unwrap();
        }
        assert_eq!(queue.pop(true).unwrap()["text"], "λ🦀");
        assert_eq!(queue.pop(true).unwrap()["text"], "\r");
        assert_eq!(queue.pop(true).unwrap()["type"], "paste");
        assert!(!queue.ready(1, 8));
        assert!(queue.ready(0, 8));
        assert_eq!(queue.pop(true).unwrap()["type"], "release");
    }
    #[test]
    fn wheel_batches_are_bounded_and_preserve_reversals_and_input_order() {
        let mut queue = Commands::default();
        let target = target("a");
        for lines in [12,12,12,-2] {
            queue.push(json!({"type":"wheel","lines":lines,"col":2}), &target).unwrap();
        }
        queue.push(json!({"type":"wheel","lines":-3,"col":4}), &target).unwrap();
        queue.push(json!({"type":"paste","text":"text after swipe"}), &target).unwrap();
        queue.push(json!({"type":"wheel","lines":-4,"col":4}), &target).unwrap();
        assert!(queue.ready(1,8));
        assert!(!queue.ready(2,8));
        let batch=queue.pop(true).unwrap();
        assert!(target.matches(&batch));
        assert_eq!(wheel_lines(&batch),32);
        assert_eq!(wheel_lines(&queue.pop(true).unwrap()),-2);
        assert_eq!(wheel_lines(&queue.pop(true).unwrap()),-3);
        assert!(!queue.ready(1,8));
        assert_eq!(queue.pop(true).unwrap()["type"],"paste");
        queue.discard_input();
        assert!(queue.pop(true).is_none());
    }
    #[test]
    fn bindings_do_not_cross_panes_reconnects_or_navigation() {
        let mut queue = Commands::default();
        let t = target("a");
        let mut other = t.clone();
        other.block = "b".into();
        queue.push(json!({"type":"input","text":"a"}), &t).unwrap();
        queue
            .push(json!({"type":"input","text":"b"}), &other)
            .unwrap();
        let first = queue.pop(true).unwrap();
        assert!(t.matches(&first));
        assert!(!other.matches(&first));
        assert_eq!(first["text"], "a");
        let mut resumed = t.clone();
        resumed.connection += 1;
        assert!(!resumed.matches(&first));
        resumed = t.clone();
        resumed.navigation += 1;
        assert!(!resumed.matches(&first));
        assert_eq!(queue.pop(true).unwrap()["text"], "b");
    }
    #[test]
    fn clicks_are_bound_ordered_barriers_and_discarded_with_stale_input() {
        let mut queue=Commands::default();let t=target("a");
        queue.push(json!({"type":"input","text":"before"}),&t).unwrap();
        queue.push(json!({"type":"click","col":4,"row":5}),&t).unwrap();
        queue.push(json!({"type":"input","text":"after"}),&t).unwrap();
        assert_eq!(queue.pop(true).unwrap()["text"],"before");
        assert!(!queue.ready(1,8));assert!(queue.ready(0,8));
        let click=queue.pop(true).unwrap();assert_eq!(click["type"],"click");assert!(t.matches(&click));
        let mut reconnected=t.clone();reconnected.connection+=1;assert!(!reconnected.matches(&click));
        assert_eq!(queue.pop(true).unwrap()["text"],"after");
        queue.push(click,&t).unwrap();queue.discard_input();assert!(queue.pop(false).is_none());
    }

    #[test]
    fn queues_are_bounded_and_input_can_be_discarded_without_replaying() {
        let mut queue = Commands::default();
        let t = target("a");
        for _ in 0..128 {
            queue.push(json!({"type":"input","text":"x"}), &t).unwrap();
        }
        assert!(
            queue
                .push(json!({"type":"input","text":"overflow"}), &t)
                .is_err()
        );
        assert!(!queue.ready(8, 8));
        queue.discard_input();
        queue
            .push(json!({"type":"input","text":"new"}), &t)
            .unwrap();
        assert_eq!(queue.pop(false).unwrap()["text"], "new");
        assert!(
            queue
                .push(json!({"type":"paste","text":"x".repeat(1_048_577)}), &t)
                .is_err()
        );
        queue.clear();
        assert!(!queue.ready(0, 8));
    }
}
