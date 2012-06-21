package org.servalproject.batphone;

import java.io.IOException;

import org.servalproject.ServalBatPhoneApplication;
import org.servalproject.servald.ServalDMonitor;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

public class AudioRecorder implements Runnable {

	boolean recording = false;
	boolean stopMe = false;
	final String call_session_token;
	Thread audioThread;
	AudioRecord audioRecorder;

	int codec = VoMP.VOMP_CODEC_PCM;
	int codecTimespan = VoMP.vompCodecTimespan(codec);
	int codecBlockBytes = VoMP.vompCodecBlockSize(codec);
	int sampleRate = 8000;

	// record a higher sample rate than we are using to reduce latency
	int downSampleCount = 1;
	int audioFrameSize = 2;

	public AudioRecorder(String token) {
		call_session_token = token;
	}

	public synchronized void startRecording() {
		if (audioThread == null) {
			audioThread = new Thread(this, "Recording");
			recording = true;
			audioThread.start();
		}
	}

	public synchronized void stopRecording() {
		stopMe = true;
		if (audioThread != null)
			audioThread.interrupt();

	}

	@Override
	public void run() {

		android.os.Process
				.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

		if (audioRecorder == null) {
			int bufferSize = AudioRecord.getMinBufferSize(sampleRate
					* downSampleCount,
					AudioFormat.CHANNEL_IN_MONO,
					AudioFormat.ENCODING_PCM_16BIT);

			// ensure 60ms minimum record buffer
			if (bufferSize < 8 * downSampleCount * 60 * audioFrameSize)
				bufferSize = 8 * downSampleCount * 60 * audioFrameSize;

			audioRecorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
					sampleRate * downSampleCount,
					AudioFormat.CHANNEL_IN_MONO,
					AudioFormat.ENCODING_PCM_16BIT, bufferSize);

			Log.d("VoMPRecorder", "Minimum record buffer is " + bufferSize
					+ " = "
							+ (bufferSize / (double) (2 * downSampleCount * sampleRate))
							+ " seconds.");

			if (audioRecorder.getState() != AudioRecord.STATE_INITIALIZED) {
				Log.v("VoMPRecorder",
						"Audio device not initialised, TODO abort call");
				audioRecorder.release();
				audioRecorder = null;
				return;
			}

		}

		// get one block of audio at a time.
		// get in byte[], even though samples are natively short[],
		// as it ends up being more efficient when we hand them to
		// a codec or just send them as raw PCM
		int bytesRead = 0;
		int blockSamples = codecTimespan * 8 * downSampleCount; // 40khz sample
																// rate, and
												// timespan
												// in ms
		int blockBytes = blockSamples * 2;
		byte[] block = new byte[blockBytes];
		Log.d("VoMPRecorder", "Starting loop");
		while (!stopMe) {
			try {
				if (recording) {
					switch (audioRecorder.getRecordingState()) {
					case AudioRecord.RECORDSTATE_STOPPED:
						Log.d("VoMPRecorder",
								"Asking audioRecorder to start recording");
						audioRecorder.startRecording();
						Thread.sleep(10);
						break;
					case AudioRecord.RECORDSTATE_RECORDING:

						if (bytesRead < block.length) {
							int bytes = audioRecorder.read(block, bytesRead,
									block.length
											- bytesRead);
							if (bytes > 0)
								// process audio block
								bytesRead += bytes;
						}
						break;
					default:
						Log.v("VoMPRecorder", "Audio recording state == "
								+ audioRecorder.getRecordingState());
						audioRecorder.release();
						audioRecorder = null;
						return;
					}


					if (bytesRead >= block.length) {
						bytesRead = 0;
						processBlock(block);
					}

				} else {
					if (audioRecorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING)
						audioRecorder.stop();
					Log.d("VoMPRecorder", "Not recording");
					Thread.sleep(100);
				}
			} catch (Exception e) {
				Log.e("VoMPRecorder", e.getMessage(), e);
			}
		}
		Log.d("VoMPRecorder", "Releasing recorder and terminating");
		if (recording)
			audioRecorder.stop();
		audioRecorder.release();
		recording = false;
		audioRecorder = null;
		audioThread = null;
	}

	int counter = 0;

	private void processBlock(byte[] block) throws IOException {
		// send block to servald via monitor interface
		ServalDMonitor m = ServalBatPhoneApplication.context.servaldMonitor;
		if (m == null)
			return;
		// only send the occassional packet to help aid debugging
		// if ((counter & 0x7) == 0)

		int l=0;
		if (downSampleCount > 1) {
			int sum = 0;
			for (int i = 0; i < block.length; i += 2) {
				sum += (block[i] & 0xFF) | (block[i + 1] << 8);
				if (i % (downSampleCount << 1) == 0) {
					int average = sum / downSampleCount;
					block[l++] = (byte) average;
					block[l++] = (byte) (average >> 8);
					sum = 0;
				}
			}
		} else
			l = block.length;
		m.sendMessageAndData(block, l, "AUDIO:", call_session_token, ":",
				Integer.toString(codec));
		// counter++;
		// Log.d("AudioRecorder", "Send block of audio");
	}

}