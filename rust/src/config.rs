//! Engine configuration, deserialized from the JSON built by Kotlin `Settings.nativeJson()`.

use serde::Deserialize;
use thiserror::Error;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ChannelLayout {
    Mono,
    Stereo,
}

impl ChannelLayout {
    #[must_use]
    pub fn channels(self) -> usize {
        match self {
            Self::Mono => 1,
            Self::Stereo => 2,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
pub enum FrameMs {
    #[serde(rename = "2.5")]
    Ms2p5,
    #[serde(rename = "5")]
    Ms5,
    #[serde(rename = "10")]
    Ms10,
    #[serde(rename = "20")]
    Ms20,
    #[serde(rename = "40")]
    Ms40,
    #[serde(rename = "60")]
    Ms60,
}

impl FrameMs {
    #[must_use]
    pub fn milliseconds(self) -> f64 {
        match self {
            Self::Ms2p5 => 2.5,
            Self::Ms5 => 5.0,
            Self::Ms10 => 10.0,
            Self::Ms20 => 20.0,
            Self::Ms40 => 40.0,
            Self::Ms60 => 60.0,
        }
    }

    /// Samples per channel in one Opus frame at `rate`.
    #[must_use]
    pub fn samples(self, rate: u32) -> usize {
        (f64::from(rate) * self.milliseconds() / 1000.0).round() as usize
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Application {
    Voip,
    Audio,
    Lowdelay,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum SignalHint {
    #[default]
    Auto,
    Voice,
    Music,
}

pub const SAMPLE_RATES: [u32; 5] = [8000, 12000, 16000, 24000, 48000];

#[derive(Debug, Clone, Copy, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct EngineConfig {
    pub sample_rate: u32,
    pub channels: ChannelLayout,
    pub frame_ms: FrameMs,
    pub bitrate_bps: u32,
    #[serde(default = "default_true")]
    pub vbr: bool,
    #[serde(default = "default_complexity")]
    pub complexity: u8,
    #[serde(default = "default_application")]
    pub application: Application,
    #[serde(default)]
    pub signal: SignalHint,
    pub buffer_seconds: u32,
}

fn default_true() -> bool {
    true
}

fn default_complexity() -> u8 {
    5
}

fn default_application() -> Application {
    Application::Voip
}

#[derive(Debug, Error)]
pub enum ConfigError {
    #[error("sample rate {0} Hz is not one of 8000/12000/16000/24000/48000")]
    UnsupportedSampleRate(u32),
    #[error("bitrate {0} bps is outside 500..=510000")]
    BitrateOutOfRange(u32),
    #[error("complexity {0} is outside 0..=10")]
    ComplexityOutOfRange(u8),
    #[error("buffer length {0} s is outside 1..=14400")]
    BufferOutOfRange(u32),
    #[error("invalid config JSON: {0}")]
    Parse(#[from] serde_json::Error),
}

impl EngineConfig {
    pub fn parse(json: &str) -> Result<Self, ConfigError> {
        let cfg: Self = serde_json::from_str(json)?;
        cfg.validate()?;
        Ok(cfg)
    }

    pub(crate) fn validate(&self) -> Result<(), ConfigError> {
        if !SAMPLE_RATES.contains(&self.sample_rate) {
            return Err(ConfigError::UnsupportedSampleRate(self.sample_rate));
        }
        if !(500..=510_000).contains(&self.bitrate_bps) {
            return Err(ConfigError::BitrateOutOfRange(self.bitrate_bps));
        }
        if self.complexity > 10 {
            return Err(ConfigError::ComplexityOutOfRange(self.complexity));
        }
        if !(1..=14_400).contains(&self.buffer_seconds) {
            return Err(ConfigError::BufferOutOfRange(self.buffer_seconds));
        }
        Ok(())
    }

    #[must_use]
    pub fn opus_channels(self) -> opusic_c::Channels {
        match self.channels {
            ChannelLayout::Mono => opusic_c::Channels::Mono,
            ChannelLayout::Stereo => opusic_c::Channels::Stereo,
        }
    }

    #[must_use]
    pub fn opus_sample_rate(self) -> opusic_c::SampleRate {
        match self.sample_rate {
            8000 => opusic_c::SampleRate::Hz8000,
            12000 => opusic_c::SampleRate::Hz12000,
            16000 => opusic_c::SampleRate::Hz16000,
            24000 => opusic_c::SampleRate::Hz24000,
            _ => opusic_c::SampleRate::Hz48000,
        }
    }

    #[must_use]
    pub fn opus_application(self) -> opusic_c::Application {
        match self.application {
            Application::Voip => opusic_c::Application::Voip,
            Application::Audio => opusic_c::Application::Audio,
            Application::Lowdelay => opusic_c::Application::LowDelay,
        }
    }

    #[must_use]
    pub fn opus_signal(self) -> opusic_c::Signal {
        match self.signal {
            SignalHint::Auto => opusic_c::Signal::Auto,
            SignalHint::Voice => opusic_c::Signal::Voice,
            SignalHint::Music => opusic_c::Signal::Music,
        }
    }
}
