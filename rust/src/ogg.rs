//! Ogg Opus muxing on top of the `ogg` crate's page writer.

use std::io::Write;

use ogg::writing::{
    PacketWriteEndInfo,
    PacketWriteEndInfo::{EndPage, EndStream, NormalPacket},
    PacketWriter,
};

use crate::engine::EngineError;

/// Page size threshold; larger pages amortize the 28-byte page header.
const PAGE_BYTES: usize = 8 * 1024;

/// Writer wrapper that counts bytes written, for save stats.
pub struct CountingWriter<W: Write> {
    inner: W,
    bytes: u64,
}

impl<W: Write> CountingWriter<W> {
    pub fn new(inner: W) -> Self {
        Self { inner, bytes: 0 }
    }

    pub fn bytes(&self) -> u64 {
        self.bytes
    }
}

impl<W: Write> Write for CountingWriter<W> {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        let n = self.inner.write(buf)?;
        self.bytes += n as u64;
        Ok(n)
    }

    fn flush(&mut self) -> std::io::Result<()> {
        self.inner.flush()
    }
}

/// Unique serial number per produced stream; Ogg requires it stable within a stream
/// and distinct between streams written by the same process.
fn next_serial() -> u32 {
    use std::sync::atomic::{AtomicU32, Ordering};
    static NEXT: AtomicU32 = AtomicU32::new(0x4543_484f); // "ECHO"
    NEXT.fetch_add(1, Ordering::Relaxed)
}

/// Writes a complete Ogg Opus logical stream (RFC 7845) from pre-encoded packets.
/// Granule positions are relative to the first written packet, so the cut point
/// becomes playback time zero.
pub fn write_opus_stream<'a, W: Write, I>(
    w: W,
    channels: u8,
    input_sample_rate: u32,
    frames: I,
) -> Result<(), EngineError>
where
    I: IntoIterator<Item = (&'a [u8], u32)>,
{
    let serial = next_serial();
    let mut writer = PacketWriter::new(w);
    writer.write_packet(opus_head(channels, input_sample_rate), serial, EndPage, 0)?;
    writer.write_packet(opus_tags(), serial, EndPage, 0)?;
    let mut granule: u64 = 0;
    let mut bytes_since_page = 0usize;
    let mut iter = frames.into_iter().peekable();
    let mut wrote = 0usize;
    while let Some((data, samples)) = iter.next() {
        granule += u64::from(samples);
        bytes_since_page += data.len();
        let info: PacketWriteEndInfo = if iter.peek().is_none() {
            EndStream
        } else if bytes_since_page >= PAGE_BYTES {
            bytes_since_page = 0;
            EndPage
        } else {
            NormalPacket
        };
        writer.write_packet(data, serial, info, granule)?;
        wrote += 1;
    }
    if wrote == 0 {
        return Err(EngineError::Empty);
    }
    Ok(())
}

fn opus_head(channels: u8, input_sample_rate: u32) -> Vec<u8> {
    let mut v = Vec::with_capacity(19);
    v.extend_from_slice(b"OpusHead");
    v.push(1); // version
    v.push(channels);
    v.extend_from_slice(&0u16.to_le_bytes()); // pre-skip: mid-stream cut has no encoder delay
    v.extend_from_slice(&input_sample_rate.to_le_bytes());
    v.extend_from_slice(&0i16.to_le_bytes()); // output gain
    v.push(0); // channel mapping family
    v
}

fn opus_tags() -> Vec<u8> {
    let vendor = b"echo-core";
    let mut v = Vec::with_capacity(8 + 4 + vendor.len() + 4);
    v.extend_from_slice(b"OpusTags");
    v.extend_from_slice(&(vendor.len() as u32).to_le_bytes());
    v.extend_from_slice(vendor);
    v.extend_from_slice(&0u32.to_le_bytes()); // user comment list length
    v
}
