#![allow(dead_code)]

use std::{
    collections::BTreeMap,
    sync::{Arc, Mutex},
};

use lazy_static::lazy_static;
use log::{debug, error, info, trace, warn, Level, Metadata, Record};
use serde::{Deserialize, Serialize};

use casperlabs_engine_shared::{
    logging::{self, Settings, TerminalLogger, PAYLOAD_KEY},
    newtypes::CorrelationId,
};

const CORRELATION_ID_KEY: &str = "correlation_id";
const LOG_MSG_TARGET: &str = "casperlabs_logging";
const MESSAGE_TEMPLATE_KEY: &str = "message_template";
const DEFAULT_MESSAGE_TEMPLATE: &str = "{message}";
const DEFAULT_MESSAGE_KEY: &str = "message";

lazy_static! {
    pub static ref BUFFER: Buffer = Buffer::default();
}

struct BufferedLogger {
    terminal_logger: TerminalLogger,
    buffer: Buffer,
}

impl BufferedLogger {
    fn new(buffer: Buffer, settings: &Settings) -> Self {
        BufferedLogger {
            terminal_logger: TerminalLogger::new(settings),
            buffer,
        }
    }
}

impl log::Log for BufferedLogger {
    fn enabled(&self, metadata: &Metadata) -> bool {
        self.terminal_logger.enabled(metadata)
    }

    fn log(&self, record: &Record) {
        if let Some(log_line_item) = self
            .terminal_logger
            .prepare_log_line(record)
            .and_then(LogLineItem::from_log_line)
        {
            self.buffer.push(log_line_item);
        }
    }

    fn flush(&self) {}
}

#[derive(Clone, Default, Debug)]
pub struct Buffer {
    items: Arc<Mutex<Vec<LogLineItem>>>,
}

impl Buffer {
    // Pushes item to buffer.
    fn push(&self, line: LogLineItem) {
        self.items.lock().unwrap().push(line);
    }

    /// Removes and returns items whose `description` field contains `description_fragment`.
    pub fn extract(&self, description_fragment: &str) -> Vec<LogLineItem> {
        let mut result = vec![];
        let mut items = self.items.lock().unwrap();
        let mut index = 0;
        while index != items.len() {
            if items[index].description.contains(description_fragment) {
                result.push(items.remove(index));
            } else {
                index += 1
            }
        }
        result
    }

    /// Removes and returns items whose `properties` have a matching `correlation_id`.
    pub fn extract_correlated(&self, target_correlation_id: CorrelationId) -> Vec<LogLineItem> {
        let target = target_correlation_id.to_string();
        let mut result = vec![];
        let mut items = self.items.lock().unwrap();
        let mut index = 0;
        while index != items.len() {
            if let Some(correlation_id) = items[index].properties.get(CORRELATION_ID_KEY) {
                if correlation_id == &target {
                    result.push(items.remove(index));
                    continue;
                }
            }
            index += 1;
        }
        result
    }
}

/// container for LogLineItem data
#[derive(Clone, Debug, Default, Hash, PartialEq, Serialize, Deserialize)]
pub struct LogLineItem {
    timestamp: String,
    process_id: u32,
    process_name: String,
    host_name: String,
    log_level: String,
    priority: u8,
    message_type: String,
    message_type_version: String,
    message_id: usize,
    description: String,
    properties: BTreeMap<String, String>,
}

impl LogLineItem {
    fn from_log_line(line: String) -> Option<LogLineItem> {
        if let Some(idx) = line.find(PAYLOAD_KEY) {
            let start = idx + PAYLOAD_KEY.len();
            let end = line.len();
            let slice = &line[start..end];
            if let Ok(log_line_item) = serde_json::from_str::<LogLineItem>(slice) {
                Some(log_line_item)
            } else {
                None
            }
        } else {
            None
        }
    }
}

pub fn set_up_logging(settings: Settings) {
    let logger = Box::new(BufferedLogger::new(BUFFER.clone(), &settings));
    let _ = logging::initialize_with_logger(logger, settings);
}

fn expected_level(level: Level) -> &'static str {
    match level {
        Level::Trace => "Trace",
        Level::Debug => "Debug",
        Level::Info => "Info",
        Level::Warn => "Warn",
        Level::Error => "Error",
    }
}

pub fn assert_log_via_macro_is_output(level: Level) {
    log_via_macro(level, true);
}

pub fn assert_log_via_macro_is_not_output(level: Level) {
    log_via_macro(level, false);
}

fn log_via_macro(level: Level, expect_output: bool) {
    let correlation_id = CorrelationId::new();

    let msg = format!("{}-level message with ID {}", level, correlation_id);
    match level {
        Level::Trace => trace!(target: LOG_MSG_TARGET, "{}", msg),
        Level::Debug => debug!(target: LOG_MSG_TARGET, "{}", msg),
        Level::Info => info!(target: LOG_MSG_TARGET, "{}", msg),
        Level::Warn => warn!(target: LOG_MSG_TARGET, "{}", msg),
        Level::Error => error!(target: LOG_MSG_TARGET, "{}", msg),
    }

    let msgs = BUFFER.extract(&correlation_id.to_string());
    if expect_output {
        assert_eq!(1, msgs.len());
        assert_eq!(msg, msgs[0].description);
        assert_eq!(expected_level(level), &msgs[0].log_level);
    } else {
        assert!(msgs.is_empty());
    }
}

pub fn assert_log_via_log_details_is_output(level: Level) {
    log_via_log_details(level, true);
}

pub fn assert_log_via_log_details_is_not_output(level: Level) {
    log_via_log_details(level, false);
}

fn log_via_log_details(level: Level, expect_output: bool) {
    let correlation_id = CorrelationId::new();

    let key = "test-code";
    let value = "abc".to_string();

    let mut properties = BTreeMap::new();
    properties.insert(key, value.clone());
    properties.insert(CORRELATION_ID_KEY, correlation_id.to_string());

    let message_template = format!("{{{}}} {{{}}}", CORRELATION_ID_KEY, key);

    logging::log_details(level, message_template.clone(), properties.clone());

    let msgs = BUFFER.extract_correlated(correlation_id);
    if expect_output {
        assert_eq!(1, msgs.len());
        let expected_description = format!("{} {}", correlation_id, value);
        assert_eq!(expected_description, msgs[0].description);
        assert_eq!(expected_level(level), &msgs[0].log_level);
        for (key, value) in properties.iter() {
            assert_eq!(value, msgs[0].properties.get(*key).unwrap());
        }
        assert_eq!(
            &message_template,
            msgs[0].properties.get(MESSAGE_TEMPLATE_KEY).unwrap()
        );
    } else {
        assert!(msgs.is_empty());
    }
}

pub fn assert_log_via_log_metric_is_output() {
    log_via_log_metric(true);
}

pub fn assert_log_via_log_metric_is_not_output() {
    log_via_log_metric(false);
}

fn log_via_log_metric(expect_output: bool) {
    let correlation_id = CorrelationId::new();
    let metric = "the metric";
    let tag = "the tag";
    let metric_key = "the metric_key";
    let metric_value = 0.999_f64;

    logging::log_metric(correlation_id, metric, tag, metric_key, metric_value);

    let msgs = BUFFER.extract_correlated(correlation_id);
    if expect_output {
        assert_eq!(1, msgs.len());

        let expected_description = format!("{} {} {}", metric, tag, metric_value);
        assert_eq!(expected_description, msgs[0].description);
        assert_eq!("Metric", &msgs[0].log_level);

        assert_eq!(
            &expected_description,
            msgs[0].properties.get(DEFAULT_MESSAGE_KEY).unwrap()
        );

        assert_eq!(
            &correlation_id.to_string(),
            msgs[0].properties.get("correlation_id").unwrap()
        );

        assert_eq!(
            &metric_value.to_string(),
            msgs[0].properties.get(metric_key).unwrap()
        );

        assert!(msgs[0].properties.contains_key("time-series-data"));

        assert_eq!(
            DEFAULT_MESSAGE_TEMPLATE,
            msgs[0].properties.get(MESSAGE_TEMPLATE_KEY).unwrap()
        );
    } else {
        assert!(msgs.is_empty());
    }
}
