use std::fmt;

#[derive(Debug, Clone, PartialEq, Eq)]
#[cfg_attr(feature = "ffi", derive(uniffi::Error))]
pub enum EngineError {
    InvalidConfig { reason: String },
    InvalidInput { reason: String },
}

impl fmt::Display for EngineError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            EngineError::InvalidConfig { reason } => write!(f, "invalid configuration: {reason}"),
            EngineError::InvalidInput { reason } => write!(f, "invalid input: {reason}"),
        }
    }
}

impl std::error::Error for EngineError {}
