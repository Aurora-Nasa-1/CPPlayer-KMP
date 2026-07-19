use std::fs::File;
use symphonia::core::audio::{AudioBufferRef, Signal};
use symphonia::core::codecs::{DecoderOptions, CODEC_TYPE_NULL};
use symphonia::core::errors::Error as SymphoniaError;
use symphonia::core::formats::FormatOptions;
use symphonia::core::io::MediaSourceStream;
use symphonia::core::meta::MetadataOptions;
use symphonia::core::probe::Hint;

use rustfft::{num_complex::Complex, FftPlanner};

pub struct AudioFeatures {
    pub bpm: f64,
    pub energy: f64,
    pub brightness: f64,
}

pub fn analyze_audio_file(path: &str) -> Result<AudioFeatures, String> {
    let src = File::open(path).map_err(|e| e.to_string())?;
    let mss = MediaSourceStream::new(Box::new(src), Default::default());

    let hint = Hint::new();
    let meta_opts: MetadataOptions = Default::default();
    let fmt_opts: FormatOptions = Default::default();

    let probed = symphonia::default::get_probe()
        .format(&hint, mss, &fmt_opts, &meta_opts)
        .map_err(|e| format!("Failed to probe format: {:?}", e))?;

    let mut format = probed.format;
    let track = format
        .tracks()
        .iter()
        .find(|t| t.codec_params.codec != CODEC_TYPE_NULL)
        .ok_or("No supported audio tracks")?;

    let dec_opts: DecoderOptions = Default::default();
    let mut decoder = symphonia::default::get_codecs()
        .make(&track.codec_params, &dec_opts)
        .map_err(|e| format!("Failed to create decoder: {:?}", e))?;

    let track_id = track.id;
    let sample_rate = track.codec_params.sample_rate.unwrap_or(44100) as f64;

    let mut all_samples: Vec<f32> = Vec::new();

    loop {
        let packet = match format.next_packet() {
            Ok(packet) => packet,
            Err(SymphoniaError::IoError(ref io_err))
                if io_err.kind() == std::io::ErrorKind::UnexpectedEof =>
            {
                break;
            }
            Err(SymphoniaError::DecodeError(_)) => continue,
            Err(_) => break, // Other errors
        };

        if packet.track_id() != track_id {
            continue;
        }

        match decoder.decode(&packet) {
            Ok(decoded) => {
                match decoded {
                    AudioBufferRef::F32(buf) => {
                        let channels = buf.spec().channels.count();
                        if channels == 0 {
                            continue;
                        }
                        let frames = buf.frames();
                        for frame in 0..frames {
                            let mut sum = 0.0;
                            for ch in 0..channels {
                                sum += buf.chan(ch)[frame];
                            }
                            all_samples.push(sum / channels as f32);
                        }
                    }
                    _ => {
                        // For simplicity, skip non-f32 buffers in this basic example,
                        // or convert them. Symphonia provides `SampleBuffer` to handle conversions.
                        let channels = decoded.spec().channels.count();
                        if channels == 0 {
                            continue;
                        }
                        let mut sample_buf = symphonia::core::audio::SampleBuffer::<f32>::new(
                            decoded.capacity() as u64,
                            *decoded.spec(),
                        );
                        sample_buf.copy_interleaved_ref(decoded);

                        let samples = sample_buf.samples();

                        for chunk in samples.chunks(channels) {
                            let sum: f32 = chunk.iter().sum();
                            all_samples.push(sum / channels as f32);
                        }
                    }
                }
            }
            Err(SymphoniaError::DecodeError(_)) => continue,
            Err(_) => break,
        }
    }

    if all_samples.is_empty() {
        return Err("No samples decoded".to_string());
    }

    let sum_squares: f32 = all_samples.iter().map(|&s| s * s).sum();
    let rms = (sum_squares / all_samples.len() as f32).sqrt();
    // Normalize energy more effectively. RMS for normal audio is often 0.1-0.3.
    let energy = (rms as f64 * 5.0).min(1.0);

    let window_size = 2048;
    let mut planner = FftPlanner::new();
    let fft = planner.plan_fft_forward(window_size);

    let mut total_centroid = 0.0;
    let mut num_windows = 0;

    let mut onset_envelope: Vec<f64> = Vec::new();

    for chunk in all_samples.chunks(window_size) {
        if chunk.len() < window_size {
            break;
        }

        let mut buffer: Vec<Complex<f32>> =
            chunk.iter().map(|&s| Complex { re: s, im: 0.0 }).collect();

        fft.process(&mut buffer);

        let mut num = 0.0;
        let mut den = 0.0;
        let mut window_energy = 0.0;

        for (i, c) in buffer.iter().take(window_size / 2).enumerate() {
            let magnitude = c.norm();
            let freq = i as f64 * sample_rate / window_size as f64;

            num += magnitude as f64 * freq;
            den += magnitude as f64;
            window_energy += magnitude as f64;
        }

        if den > 0.0 {
            total_centroid += num / den;
            num_windows += 1;
        }

        onset_envelope.push(window_energy);
    }

    let brightness = if num_windows > 0 {
        total_centroid / num_windows as f64
    } else {
        0.0
    };

    let mut bpm = 120.0;
    if onset_envelope.len() > 20 {
        let mut flux = Vec::new();
        // Moving average for adaptive threshold
        let mut sum_flux = 0.0;
        for i in 1..onset_envelope.len() {
            let diff = onset_envelope[i] - onset_envelope[i - 1];
            let val = if diff > 0.0 { diff } else { 0.0 };
            flux.push(val);
            sum_flux += val;
        }

        let avg_flux = sum_flux / flux.len() as f64;
        let mut peaks = Vec::new();

        for i in 1..flux.len() - 1 {
            if flux[i] > flux[i - 1] && flux[i] > flux[i + 1] && flux[i] > avg_flux * 1.5 {
                peaks.push(i);
            }
        }

        if peaks.len() > 2 {
            let mut intervals = Vec::new();
            for i in 1..peaks.len() {
                let interval = peaks[i] - peaks[i - 1];
                if interval > 5 { // Avoid too fast onsets (noise)
                    intervals.push(interval);
                }
            }

            if !intervals.is_empty() {
                intervals.sort_unstable();
                let median_interval = intervals[intervals.len() / 2];

                let time_per_window = window_size as f64 / sample_rate;
                let time_per_beat = median_interval as f64 * time_per_window;
                if time_per_beat > 0.0 {
                    let mut calculated_bpm = 60.0 / time_per_beat;
                    // Clamp to common ranges
                    while calculated_bpm < 60.0 { calculated_bpm *= 2.0; }
                    while calculated_bpm > 180.0 { calculated_bpm /= 2.0; }
                    bpm = calculated_bpm;
                }
            }
        }
    }

    Ok(AudioFeatures {
        bpm,
        energy,
        brightness,
    })
}
