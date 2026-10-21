use echo_core::{config::EngineConfig, engine::Engine};
use ogg::reading::PacketReader;

const CFG: &str = r#"{
    "sample_rate": 16000,
    "channels": "mono",
    "frame_ms": "20",
    "bitrate_bps": 16000,
    "vbr": true,
    "complexity": 5,
    "application": "voip",
    "signal": "auto",
    "buffer_seconds": 3
}"#;

/// 200 ms of 440 Hz sine as little-endian PCM16.
fn pcm_sine(rate: u32, ms: usize) -> Vec<u8> {
    let samples = rate as usize * ms / 1000;
    let mut v = Vec::with_capacity(samples * 2);
    for i in 0..samples {
        let t = i as f32 / rate as f32;
        let s = (t * 2.0 * std::f32::consts::PI * 440.0).sin() * 8000.0;
        v.extend_from_slice(&(s as i16).to_le_bytes());
    }
    v
}

fn push_seconds(engine: &mut Engine, secs: f64) {
    let chunks = (secs / 0.2).round() as usize;
    for _ in 0..chunks {
        engine.push_pcm(&pcm_sine(16000, 200)).unwrap();
    }
}

#[test]
fn ring_respects_capacity() {
    let mut engine = Engine::new(EngineConfig::parse(CFG).unwrap()).unwrap();
    push_seconds(&mut engine, 5.0);
    let st = engine.stats();
    assert!(
        st.buffered_seconds <= 3.05,
        "buffered {}",
        st.buffered_seconds
    );
    assert!(
        st.buffered_seconds >= 2.95,
        "buffered {}",
        st.buffered_seconds
    );
    assert!(st.frames_dropped > 0);
    assert_eq!(st.frames_encoded, 250); // 5 s of 20 ms frames
}

#[test]
fn save_writes_valid_ogg_opus() {
    let mut engine = Engine::new(EngineConfig::parse(CFG).unwrap()).unwrap();
    push_seconds(&mut engine, 2.0);
    let mut out = Vec::new();
    let res = engine.save(1.0, &mut out).unwrap();
    // 1.0 s of 20 ms frames cut on a frame boundary.
    assert_eq!(res.frames, 50);
    assert!(
        (res.seconds_saved - 1.0).abs() < 1e-6,
        "{}",
        res.seconds_saved
    );
    assert!(res.bytes_written > 500);

    let mut reader = PacketReader::new(std::io::Cursor::new(&out));
    let head = reader.read_packet().unwrap().unwrap();
    assert_eq!(&head.data[..8], b"OpusHead");
    assert_eq!(head.data[9], 1); // mono
    let tags = reader.read_packet().unwrap().unwrap();
    assert_eq!(&tags.data[..8], b"OpusTags");

    let mut packets = Vec::new();
    let mut last_gp = 0u64;
    while let Some(p) = reader.read_packet().unwrap() {
        let gp = p.absgp_page();
        assert!(gp >= last_gp, "granule must be non-decreasing");
        last_gp = gp;
        packets.push(p.data.clone());
    }
    assert!(packets.len() == 50, "packets {}", packets.len());
    let seconds = last_gp as f64 / 16000.0;
    assert!((seconds - 1.0).abs() < 0.03, "granule seconds {seconds}");

    // Decode the whole stream back and check the sample count and non-silence.
    let mut decoder =
        opusic_c::Decoder::new(opusic_c::Channels::Mono, opusic_c::SampleRate::Hz16000).unwrap();
    let mut pcm = Vec::new();
    let mut buf = vec![0u16; 1600]; // one frame of 20 ms at 16 kHz, plus slack
    for packet in &packets {
        let n = decoder.decode_to_slice(packet, &mut buf, false).unwrap();
        assert!(n > 0, "packet decoded to zero samples");
        pcm.extend(buf[..n].iter().map(|&u| u as i16));
    }
    let expected = 16000usize;
    assert!(
        (pcm.len() as i64 - expected as i64).abs() < 1600,
        "decoded {} samples",
        pcm.len()
    );
    let peak = pcm.iter().map(|s| s.unsigned_abs()).max().unwrap();
    assert!(peak > 1000, "decoded signal too quiet: {peak}");
}
