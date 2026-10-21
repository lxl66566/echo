//! Opus encoding ring buffer: the instant-replay core.

use std::{collections::VecDeque, io::Write, time::Instant};

use opusic_c::{Bitrate, Encoder};
use serde::Serialize;
use thiserror::Error;

use crate::{config::EngineConfig, ogg};

/// One encoded Opus packet plus its sample count.
struct Frame {
    samples: u32,
    data: Vec<u8>,
}

#[derive(Debug, Error)]
pub enum EngineError {
    #[error(transparent)]
    Config(#[from] crate::config::ConfigError),
    // opusic ErrorCode does not implement std::error::Error, hence a manual From.
    #[error("opus error: {}", .0.message())]
    Opus(opusic_c::ErrorCode),
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
    #[error("ring buffer is empty")]
    Empty,
    #[error("{0}")]
    Other(String),
}

impl From<opusic_c::ErrorCode> for EngineError {
    fn from(e: opusic_c::ErrorCode) -> Self {
        Self::Opus(e)
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct EngineStats {
    pub buffered_seconds: f64,
    pub capacity_seconds: f64,
    pub ring_frames: usize,
    pub ring_bytes: u64,
    pub frames_encoded: u64,
    pub frames_dropped: u64,
    pub encoded_bytes: u64,
    pub uptime_seconds: f64,
}

#[derive(Debug, Clone, Serialize)]
pub struct SaveResult {
    pub seconds_saved: f64,
    pub bytes_written: u64,
    pub frames: usize,
}

#[derive(Default)]
struct Counters {
    frames_encoded: u64,
    frames_dropped: u64,
    encoded_bytes: u64,
}

pub struct Engine {
    cfg: EngineConfig,
    encoder: Encoder,
    frames: VecDeque<Frame>,
    /// Samples per channel currently held in the ring.
    ring_samples: u64,
    capacity_samples: u64,
    /// Samples per channel fed to the encoder so far; granule of the next frame end.
    granule: u64,
    /// Leftover interleaved input samples that have not filled a frame yet.
    pending: Vec<u16>,
    samples_per_frame: usize,
    frame_stride: usize,
    bytes_per_sample: usize,
    counters: Counters,
    started_at: Instant,
}

impl Engine {
    pub fn new(cfg: EngineConfig) -> Result<Self, EngineError> {
        cfg.validate()?;
        let mut encoder = Encoder::new(
            cfg.opus_channels(),
            cfg.opus_sample_rate(),
            cfg.opus_application(),
        )?;
        encoder.set_bitrate(Bitrate::Value(cfg.bitrate_bps))?;
        encoder.set_vbr(cfg.vbr)?;
        encoder.set_complexity(cfg.complexity)?;
        encoder.set_signal(cfg.opus_signal())?;
        let samples_per_frame = cfg.frame_ms.samples(cfg.sample_rate);
        let channels = cfg.channels.channels();
        Ok(Self {
            capacity_samples: u64::from(cfg.buffer_seconds) * u64::from(cfg.sample_rate),
            samples_per_frame,
            frame_stride: samples_per_frame * channels,
            bytes_per_sample: channels * 2,
            pending: Vec::with_capacity(samples_per_frame * channels * 2),
            encoder,
            cfg,
            frames: VecDeque::new(),
            ring_samples: 0,
            granule: 0,
            counters: Counters::default(),
            started_at: Instant::now(),
        })
    }

    /// Feeds native-endian interleaved PCM16; a trailing partial frame is carried over.
    pub fn push_pcm(&mut self, bytes: &[u8]) -> Result<(), EngineError> {
        let usable = bytes.len() - bytes.len() % self.bytes_per_sample;
        self.pending.reserve(usable / 2);
        for word in bytes[..usable].chunks_exact(2) {
            // Android ABIs and x86 hosts are little-endian.
            self.pending
                .push(i16::from_le_bytes([word[0], word[1]]) as u16);
        }
        let mut consumed = 0usize;
        let mut pkt = [0u8; 1275]; // maximum Opus packet size
        while self.pending.len() - consumed >= self.frame_stride {
            let window = &self.pending[consumed..consumed + self.frame_stride];
            let n = self.encoder.encode_to_slice(window, &mut pkt)?;
            consumed += self.frame_stride;
            self.counters.frames_encoded += 1;
            self.counters.encoded_bytes += n as u64;
            self.frames.push_back(Frame {
                samples: self.samples_per_frame as u32,
                data: pkt[..n].to_vec(),
            });
            self.granule += self.samples_per_frame as u64;
            self.ring_samples += self.samples_per_frame as u64;
        }
        self.pending.drain(..consumed);
        while self.ring_samples > self.capacity_samples {
            if let Some(frame) = self.frames.pop_front() {
                self.ring_samples -= u64::from(frame.samples);
                self.counters.frames_dropped += 1;
            } else {
                break;
            }
        }
        Ok(())
    }

    /// Writes the last `seconds` of the ring to `w` as an Ogg Opus stream.
    /// A partially covered leading frame is included whole.
    pub fn save<W: Write>(&mut self, seconds: f64, w: W) -> Result<SaveResult, EngineError> {
        if self.frames.is_empty() {
            return Err(EngineError::Empty);
        }
        let rate = u64::from(self.cfg.sample_rate);
        let want = ((seconds.max(0.0) * rate as f64).round() as u64).clamp(1, self.ring_samples);
        let skip = self.ring_samples - want;
        let mut idx = 0usize;
        let mut skip_left = skip;
        while skip_left >= u64::from(self.frames[idx].samples) {
            skip_left -= u64::from(self.frames[idx].samples);
            idx += 1;
        }
        let saved_samples: u64 = self
            .frames
            .iter()
            .skip(idx)
            .map(|f| u64::from(f.samples))
            .sum();
        let count = self.frames.len() - idx;
        let mut counter = ogg::CountingWriter::new(w);
        ogg::write_opus_stream(
            &mut counter,
            self.cfg.channels.channels() as u8,
            self.cfg.sample_rate,
            self.frames
                .iter()
                .skip(idx)
                .map(|f| (f.data.as_slice(), f.samples)),
        )?;
        counter.flush()?;
        Ok(SaveResult {
            seconds_saved: saved_samples as f64 / rate as f64,
            bytes_written: counter.bytes(),
            frames: count,
        })
    }

    #[must_use]
    pub fn stats(&self) -> EngineStats {
        EngineStats {
            buffered_seconds: self.ring_samples as f64 / f64::from(self.cfg.sample_rate),
            capacity_seconds: self.capacity_samples as f64 / f64::from(self.cfg.sample_rate),
            ring_frames: self.frames.len(),
            ring_bytes: self.frames.iter().map(|f| f.data.len() as u64).sum(),
            frames_encoded: self.counters.frames_encoded,
            frames_dropped: self.counters.frames_dropped,
            encoded_bytes: self.counters.encoded_bytes,
            uptime_seconds: self.started_at.elapsed().as_secs_f64(),
        }
    }

    #[cfg(test)]
    #[allow(dead_code)]
    fn granule(&self) -> u64 {
        self.granule
    }
}
