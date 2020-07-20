mod common;

use lazy_static::lazy_static;
use log::{Level, LevelFilter};

use casperlabs_engine_shared::logging::Settings;

lazy_static! {
    static ref ERROR_WITHOUT_METRICS: Settings =
        Settings::new(LevelFilter::Error).with_metrics_enabled(false);
}

#[test]
fn should_log_via_macros() {
    common::set_up_logging(*ERROR_WITHOUT_METRICS);

    common::assert_log_via_macro_is_not_output(Level::Trace);
    common::assert_log_via_macro_is_not_output(Level::Debug);
    common::assert_log_via_macro_is_not_output(Level::Info);
    common::assert_log_via_macro_is_not_output(Level::Warn);

    common::assert_log_via_macro_is_output(Level::Error);
}

#[test]
fn should_log_via_log_details() {
    common::set_up_logging(*ERROR_WITHOUT_METRICS);

    common::assert_log_via_log_details_is_not_output(Level::Trace);
    common::assert_log_via_log_details_is_not_output(Level::Debug);
    common::assert_log_via_log_details_is_not_output(Level::Info);
    common::assert_log_via_log_details_is_not_output(Level::Warn);

    common::assert_log_via_log_details_is_output(Level::Error);
}

#[test]
fn should_log_via_log_metric() {
    common::set_up_logging(*ERROR_WITHOUT_METRICS);
    common::assert_log_via_log_metric_is_not_output();
}
