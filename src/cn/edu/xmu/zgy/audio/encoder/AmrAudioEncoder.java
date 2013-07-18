package cn.edu.xmu.zgy.audio.encoder;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

import cn.edu.xmu.zgy.config.CommonConfig;

import android.app.Activity;
import android.media.MediaRecorder;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;
import android.widget.Toast;


public class AmrAudioEncoder {
	private static final String TAG = "ArmAudioEncoder";

	private static AmrAudioEncoder amrAudioEncoder = null;

	private Activity activity;

	private MediaRecorder mediaRecorder;

	private boolean isAudioRecording;

	private LocalServerSocket lss;
	private LocalSocket sender, receiver;

	private AmrAudioEncoder() {
	}

    /**
     * singleton method,return a instance of the class
     * @return singleton instance of class
     */
	public static AmrAudioEncoder getArmAudioEncoderInstance() {
		if (amrAudioEncoder == null) {
			synchronized (AmrAudioEncoder.class) {
				if (amrAudioEncoder == null) {
					amrAudioEncoder = new AmrAudioEncoder();
				}
			}
		}
		return amrAudioEncoder;
	}

    /**
     * initialize an encoder
     * @param activity
     */
	public void initArmAudioEncoder(Activity activity) {
		this.activity = activity;
		isAudioRecording = false;
	}

    /**
     * check if encoder is ready,and start recording if is ready
     */
	public void start() {
		if (activity == null) {
			showToastText("activity null");
			return;
		}

		if (isAudioRecording) {
			showToastText("init already started");
			return;
		}

		if (!initLocalSocket()) {
			showToastText("local service failed");
			releaseAll();
			return;
		}

		if (!initAudioRecorder()) {
			showToastText("init failed");
			releaseAll();
			return;
		}

		this.isAudioRecording = true;
		startAudioRecording();
	}

    /**
     * initialize localsocket(after release existing localsocket) pair, include a serversocket and localsocket
     * receiver is the localsocket,and sender is accepted from serversocket
     * @return
     */
	private boolean initLocalSocket() {
		boolean ret = true;
		try {
			releaseLocalSocket();

			String serverName = "armAudioServer";
			final int bufSize = 1024;

			lss = new LocalServerSocket(serverName);

			receiver = new LocalSocket();
			receiver.connect(new LocalSocketAddress(serverName));
			receiver.setReceiveBufferSize(bufSize);
			receiver.setSendBufferSize(bufSize);

			sender = lss.accept();
			sender.setReceiveBufferSize(bufSize);
			sender.setSendBufferSize(bufSize);
		} catch (IOException e) {
			ret = false;
		}
		return ret;
	}

    /**
     * initialize and start media recoder
     * @return
     */
	private boolean initAudioRecorder() {
		if (mediaRecorder != null) {
			mediaRecorder.reset();
			mediaRecorder.release();
		}
		mediaRecorder = new MediaRecorder();
		mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
		mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.RAW_AMR);
		final int mono = 1;
		mediaRecorder.setAudioChannels(mono);
		mediaRecorder.setAudioSamplingRate(8000);
		mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
		mediaRecorder.setOutputFile(sender.getFileDescriptor());

		boolean ret = true;
		try {
			mediaRecorder.prepare();
			mediaRecorder.start();
		} catch (Exception e) {
			releaseMediaRecorder();
			showToastText("amr not supported");
			ret = false;
		}
		return ret;
	}

    /**
     * start execute an AudioCaptureAndSend thread
     */
	private void startAudioRecording() {
		new Thread(new AudioCaptureAndSendThread()).start();
	}

    /**
     * release all resource and set flag isAudioRecording to false
     */
	public void stop() {
		if (isAudioRecording) {
            mediaRecorder.stop();
			isAudioRecording = false;
		}
		releaseAll();
	}

    /**
     * release media recoder,local socket and singleton instance     *
     */
	private void releaseAll() {
		releaseMediaRecorder();
		releaseLocalSocket();
		amrAudioEncoder = null;
	}

    /**
     *reset and release media recoder
     */
	private void releaseMediaRecorder() {
		try {
			if (mediaRecorder == null) {
				return;
			}
			if (isAudioRecording) {
				mediaRecorder.stop();
				isAudioRecording = false;
			}
			mediaRecorder.reset();
			mediaRecorder.release();
			mediaRecorder = null;
		} catch (Exception err) {
			Log.d(TAG, err.toString());
		}
	}

    /**
     * release locale socket
     */
	private void releaseLocalSocket() {
		try {
			if (sender != null) {
				sender.close();
			}
			if (receiver != null) {
				receiver.close();
			}
			if (lss != null) {
				lss.close();
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		sender = null;
		receiver = null;
		lss = null;
	}


	private boolean isAudioRecording() {
		return isAudioRecording;
	}

	private void showToastText(String msg) {
		Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
	}

	private class AudioCaptureAndSendThread implements Runnable {
		public void run() {
			try {
				sendAmrAudio();
			} catch (Exception e) {
				Log.e(TAG, "sendAmrAudio failed");
			}
		}

		private void sendAmrAudio() throws Exception {
			DatagramSocket udpSocket = new DatagramSocket();
			DataInputStream dataInput = new DataInputStream(receiver.getInputStream());

			skipAmrHead(dataInput);

			final int SEND_FRAME_COUNT_ONE_TIME = 10;// 10frames send per time，1frame 32B
			// http://blog.csdn.net/dinggo/article/details/1966444
			final int BLOCK_SIZE[] = { 12, 13, 15, 17, 19, 20, 26, 31, 5, 0, 0, 0, 0, 0, 0, 0 };

			byte[] sendBuffer = new byte[1024];
			while (isAudioRecording()) {
				int offset = 0;
				for (int index = 0; index < SEND_FRAME_COUNT_ONE_TIME; ++index) {
					if (!isAudioRecording()) {
						break;
					}
					dataInput.read(sendBuffer, offset, 1);
					int blockIndex = (int) (sendBuffer[offset] >> 3) & 0x0F;
					int frameLength = BLOCK_SIZE[blockIndex];
					readSomeData(sendBuffer, offset + 1, frameLength, dataInput);
					offset += frameLength + 1;
				}
				udpSend(udpSocket, sendBuffer, offset);
			}
			udpSocket.close();
			dataInput.close();
			releaseAll();
		}

		private void skipAmrHead(DataInputStream dataInput) {
			final byte[] AMR_HEAD = new byte[] { 0x23, 0x21, 0x41, 0x4D, 0x52, 0x0A };
			int result = -1;
			int state = 0;
			try {
				while (-1 != (result = dataInput.readByte())) {
					if (AMR_HEAD[0] == result) {
						state = (0 == state) ? 1 : 0;
					} else if (AMR_HEAD[1] == result) {
						state = (1 == state) ? 2 : 0;
					} else if (AMR_HEAD[2] == result) {
						state = (2 == state) ? 3 : 0;
					} else if (AMR_HEAD[3] == result) {
						state = (3 == state) ? 4 : 0;
					} else if (AMR_HEAD[4] == result) {
						state = (4 == state) ? 5 : 0;
					} else if (AMR_HEAD[5] == result) {
						state = (5 == state) ? 6 : 0;
					}

					if (6 == state) {
						break;
					}
				}
			} catch (Exception e) {
				Log.e(TAG, "read mdat error");
			}
		}

		private void readSomeData(byte[] buffer, int offset, int length, DataInputStream dataInput) {
			int numOfRead = -1;
			while (true) {
				try {
					numOfRead = dataInput.read(buffer, offset, length);
					if (numOfRead == -1) {
						Log.d(TAG, "amr no data get wait for data coming");
						Thread.sleep(100);
					} else {
						offset += numOfRead;
						length -= numOfRead;
						if (length <= 0) {
							break;
						}
					}
				} catch (Exception e) {
					Log.e(TAG, "amr error readSomeData");
					break;
				}
			}
		}

		private void udpSend(DatagramSocket udpSocket, byte[] buffer, int sendLength) {
			try {
				InetAddress ip = InetAddress.getByName(CommonConfig.SERVER_IP_ADDRESS.trim());
				int port = CommonConfig.AUDIO_SERVER_UP_PORT;

				byte[] sendBuffer = new byte[sendLength];
				System.arraycopy(buffer, 0, sendBuffer, 0, sendLength);

				DatagramPacket packet = new DatagramPacket(sendBuffer, sendLength);
				packet.setAddress(ip);
				packet.setPort(port);
				udpSocket.send(packet);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
}
