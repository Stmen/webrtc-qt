package com.video.play;

import java.util.ArrayList;
import java.util.HashMap;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.MessageQueue.IdleHandler;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.support.v4.view.ViewPager.LayoutParams;
import android.view.Display;
import android.view.GestureDetector;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;
import android.widget.Toast;

import com.video.R;
import com.video.data.Value;
import com.video.service.BackstageService;

@SuppressLint({ "Wakelock", "HandlerLeak" })
public class RemoteFilePlayerActivity  extends Activity implements OnClickListener  {

	private static Context mContext;
	
	private static VideoView videoView = null; //视频对象
	private static AudioThread audioThread = null; //音频对象
	private WakeLock wakeLock = null; //锁屏对象
	
	public static int requestPlayerTimes = 0;
	private PlayerReceiver playerReceiver; 
	public static final String REQUEST_REMOTE_FILE_PLAYER_ACTION = "RemoteFilePlayerActivity.requestRemoteFilePlayerStatus";

	private FileInfo fileInfo = null;
	private static String dealerName = null;
	private static Dialog mDialog = null;
	
	private int screenWidth = 0;
	private int screenHeight = 0;
	private int bottomHeight = 100;
	
	private boolean isPopupWindowShow = false;
	private final int SHOW_TIME_MS = 10000;
	private final int HIDE_POPUPWINDOW = 1;
	private final int REQUEST_TIME_OUT = 2;
	private final int SEEKBAR_REFRESH = 3;
	
	private final int PAUSE_PLAY_REMOTE_FILE = 0;
	private final int CONTINUE_PLAY_REMOTE_FILE = 100;
	
	private GestureDetector mGestureDetector = null; //手势识别
	private int dragSeekBarProgress = 0; // 当前播放进度
	
	private View controlerView = null; //底部视图
	private static PopupWindow controlerPopupWindow = null; //底部弹出框
	private View infoView = null; // 播放信息视图
	private static PopupWindow infoPopupWindow = null; // 播放信息弹出框

	private SeekBar seekBar = null; // 可拖拽的进度条
	private TextView tv_info = null; // 播放信息
	private ImageButton button_front = null; // 上一个
	private ImageButton button_play_pause = null; // 播放、暂停
	private ImageButton button_back = null; // 下一个
	
	public ArrayList<HashMap<String, String>> fileList = new ArrayList<HashMap<String, String>>();
	public class FileInfo {
		public int fileIndex = 0; // 播放列表索引
		public String fileName = ""; // 文件名
		public long fileSize = 100; // 文件大小
		public int playSpeed = 0; // 播放速度
		public int lastPosition = 0; // 上次播放指针
	}
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		// TODO Auto-generated method stub
		super.onCreate(savedInstanceState);
		PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
		wakeLock = pm.newWakeLock(PowerManager.ACQUIRE_CAUSES_WAKEUP|PowerManager.FULL_WAKE_LOCK, "WakeLock");
		wakeLock.acquire(); // 设置屏幕保持唤醒
		
		// 视频
		videoView = new VideoView(this);
		setContentView(videoView);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		
		// 音频
		audioThread = new AudioThread();
		
		initData(); // 初始化数据
		initView(); // 初始化视图
		
		// 空闲的队列
		Looper.myQueue().addIdleHandler(new IdleHandler() {
			@Override
			public boolean queueIdle() {
				isPopupWindowShow = true;
				if (controlerPopupWindow != null && videoView.isShown()) {
					showBottomPopupWindow();
					showRecordPopupWindow();
				}
				hidePopupWindowDelay();
				return false;
			}
		});
		
		mGestureDetector = new GestureDetector(new SimpleOnGestureListener(){
			//单击屏幕
			@Override
			public boolean onSingleTapUp(MotionEvent e) {
				// TODO Auto-generated method stub
				if (isPopupWindowShow) {
					hidePopupWindow();
				} else {
					showPopupWindow();
					hidePopupWindowDelay();
				}
				return false;
			}
		});
	}
	
	private void initView() {
		// 视频底部弹出框
		controlerView = getLayoutInflater().inflate(R.layout.terminal_player_controler, null);
		controlerPopupWindow = new PopupWindow(controlerView, screenWidth, bottomHeight, false);
		
		seekBar = (SeekBar) controlerView.findViewById(R.id.seekbar);
		seekBar.setOnSeekBarChangeListener(new onSeekBarChangeListenerImpl());
		
		button_front = (ImageButton) controlerView.findViewById(R.id.ib_front_file);
		button_play_pause = (ImageButton) controlerView.findViewById(R.id.ib_play_pause);
		button_back = (ImageButton) controlerView.findViewById(R.id.ib_back_file);
		
		button_front.setOnClickListener(this);
		button_play_pause.setOnClickListener(this);
		button_back.setOnClickListener(this);
		
		button_front.setAlpha(0xBB);
		button_play_pause.setAlpha(0xBB);
		button_back.setAlpha(0xBB);
		
		// 录像弹出框
		infoView = getLayoutInflater().inflate(R.layout.terminal_player_info, null);
		infoPopupWindow = new PopupWindow(infoView, screenWidth, bottomHeight, false);
		tv_info = (TextView) infoView.findViewById(R.id.tv_info_content);
		tv_info.setText("正在播放");
		
		// 处理显示
		seekBar.setMax((int)fileInfo.fileSize);
	}
	
	@SuppressWarnings("unchecked")
	private void initData() {
		mContext = RemoteFilePlayerActivity.this;
		getScreenSize();
		
		//注册广播
		playerReceiver = new PlayerReceiver();
		IntentFilter filter = new IntentFilter();
		filter.addAction(REQUEST_REMOTE_FILE_PLAYER_ACTION);
		filter.addAction(BackstageService.TUNNEL_REQUEST_ACTION);
		registerReceiver(playerReceiver, filter);
		
		//获得终端录像的信息		
		Intent intent = this.getIntent();
		if (intent != null) {
			fileInfo = new FileInfo();
			dealerName = (String) intent.getCharSequenceExtra("dealerName");
			fileList = (ArrayList<HashMap<String, String>>) intent.getSerializableExtra("fileList");
			fileInfo.fileIndex = intent.getIntExtra("fileIndex", 0);
			fileInfo.fileName = fileList.get(fileInfo.fileIndex).get("fileName"); 
			
			//【请求远程录像】
			TunnelCommunication.getInstance().playRemoteFile(dealerName, fileInfo.fileName);
			sendHandlerMsg(REQUEST_TIME_OUT, Value.REQ_TIME_15S);
			if ((mDialog == null) || (!mDialog.isShowing())) {
				mDialog = createLoadingDialog("正在请求远程录像...");
				mDialog.show();
			}
		}
	}
	
	private class onSeekBarChangeListenerImpl implements OnSeekBarChangeListener {
		@Override
		public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
			// TODO Auto-generated method stub
			if (fromUser) {
				dragSeekBarProgress = progress;
			}
		}
		
		@Override
		public void onStartTrackingTouch(SeekBar seekBar) {
			// TODO Auto-generated method stub
		}
		
		@Override
		public void onStopTrackingTouch(SeekBar seekBar) {
			// TODO Auto-generated method stub
			TunnelCommunication.getInstance().setPlayPosition(dealerName, dragSeekBarProgress);
			System.out.println("MyDebug: 【拖拽指针位置】"+dragSeekBarProgress);
			if (TunnelCommunication.videoDataCache != null) {
				TunnelCommunication.videoDataCache.clearBuffer();
			}
			if (TunnelCommunication.audioDataCache != null) {
				TunnelCommunication.audioDataCache.clearBuffer();
			}
			if (videoView.isPlayVideo) {
				playTerminalVideoFile();
			} else {
				pauseTerminalVideoFile();
			}
			hidePopupWindowDelay();
		}
	}
	
	/**
	 * -1:关闭播放器  0:正常播放  1:前面没有了  2:后面没有了
	 */
	private int playFrontOrBackFile(boolean isFrontFile) {
		int fileSize = fileList.size();
		if (fileSize <= 0) {
			RemoteFilePlayerActivity.this.finish();
			return -1;
		}
		
		int index = fileInfo.fileIndex;
		if (isFrontFile) {
			index --;
		} else {
			index ++;
		}
		
		if (index < 0) {
			toastNotify(mContext, "前面没有了", Toast.LENGTH_SHORT);
			return 1;
		}
		else if (index >= fileSize) {
			toastNotify(mContext, "后面没有了", Toast.LENGTH_SHORT);
			return 2;
		} else {
			closePlayer();
			fileInfo.fileIndex = index;
			fileInfo.fileName = fileList.get(index).get("fileName");
			//【请求远程录像】
			TunnelCommunication.getInstance().playRemoteFile(dealerName, fileInfo.fileName);
			sendHandlerMsg(REQUEST_TIME_OUT, Value.REQ_TIME_15S);
			if ((mDialog == null) || (!mDialog.isShowing())) {
				if (isFrontFile) {
					mDialog = createLoadingDialog("正在请求前一个远程录像...");
				} else {
					mDialog = createLoadingDialog("正在请求后一个远程录像...");
				}
				mDialog.show();
			}
		}
		return 0;
	}
	
	/**
	 * 播放终端录像
	 */
	private void playTerminalVideoFile() {
		TunnelCommunication.getInstance().setPlaySpeed(dealerName, CONTINUE_PLAY_REMOTE_FILE);
		button_play_pause.setImageResource(R.drawable.local_player_pause);
		tv_info.setText("正在播放");
	}
	
	/**
	 * 暂停终端录像
	 */
	private void pauseTerminalVideoFile() {
		TunnelCommunication.getInstance().setPlaySpeed(dealerName, PAUSE_PLAY_REMOTE_FILE);
		button_play_pause.setImageResource(R.drawable.local_player_play);
		tv_info.setText("已暂停");
	}
	
	@Override
	public void onClick(View v) {
		// TODO Auto-generated method stub
		switch (v.getId()) {
			// 前一个文件
			case R.id.ib_front_file:
				playFrontOrBackFile(true);
				break;
			// 播放、暂停
			case R.id.ib_play_pause:
				if (videoView.isPlayVideo) {
					pauseTerminalVideoFile();
				} else {
					playTerminalVideoFile();
				}
				videoView.isPlayVideo = !videoView.isPlayVideo;
				break;
			// 后一个文件
			case R.id.ib_back_file:
				playFrontOrBackFile(false);
				break;
		}
		hidePopupWindowDelay();
	}
	
	@Override
	public boolean onTouchEvent(MotionEvent event) {// 实现该方法来处理触屏事件
		// TODO Auto-generated method stub
		boolean result = mGestureDetector.onTouchEvent(event);
		if (!result) {
			result = super.onTouchEvent(event);
		}
		return result;
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		// TODO Auto-generated method stub
		if (keyCode == KeyEvent.KEYCODE_BACK  && event.getRepeatCount() == 0) {
			RemoteFilePlayerActivity.this.finish();
		}
		return super.onKeyDown(keyCode, event);
	}
	
	private Handler handler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			// TODO Auto-generated method stub
			super.handleMessage(msg);
			switch (msg.what) {
				// 隐藏弹出框
				case HIDE_POPUPWINDOW:
					hidePopupWindow();
					break;
				// 请求超时
				case REQUEST_TIME_OUT:
					if ((mDialog != null) && (mDialog.isShowing())) {
						mDialog.dismiss();
						mDialog = null;
					}
					toastNotify(mContext, "请求远程录像超时，请重试！", Toast.LENGTH_SHORT);
					RemoteFilePlayerActivity.this.finish();
					break;
				// 更新播放进度条
				case SEEKBAR_REFRESH:
					if (msg.arg1 < 0) {
						seekBar.setProgress(fileInfo.lastPosition);
					} else {
						seekBar.setProgress(msg.arg1);
					}
					break;
			}
		}
	};
	
	/**
	 * 发送Handler消息
	 */
	public void sendHandlerMsg(int what) {
		Message msg = new Message();
		msg.what = what;
		handler.sendMessage(msg);
	}
	private void sendHandlerMsg(int what, int timeout) {
		Message msg = new Message();
		msg.what = what;
		handler.sendMessageDelayed(msg, timeout);
	}
	public void sendHandlerMsg(Handler handler, int what, int arg1) {
		Message msg = new Message();
		msg.what = what;
		msg.arg1 = arg1;
		handler.sendMessage(msg);
	}
	public void sendHandlerMsg(Handler handler, int what, int arg1, int arg2) {
		Message msg = new Message();
		msg.what = what;
		msg.arg1 = arg1;
		msg.arg2 = arg2;
		handler.sendMessage(msg);
	}
	public void sendHandlerMsg(Handler handler, int what, HashMap<String, String> obj) {
		Message msg = new Message();
		msg.what = what;
		msg.obj = obj;
		handler.sendMessage(msg);
	}
	
	/**
	 * 显示PopupWindow
	 */
	private void showPopupWindow() {
		isPopupWindowShow = true;
		cancelDelayHide();
		showBottomPopupWindow();
	}
	
	/**
	 * 显示底部PopupWindow
	 */
	private void showBottomPopupWindow() {
		controlerPopupWindow.setHeight(bottomHeight); 
		controlerPopupWindow.setBackgroundDrawable(new BitmapDrawable());
		controlerPopupWindow.setOutsideTouchable(true);
		
		controlerPopupWindow.setAnimationStyle(R.style.PopupAnimationBottom);
		controlerPopupWindow.showAtLocation(videoView, Gravity.BOTTOM, 0, 0);
		controlerPopupWindow.update();
		controlerPopupWindow.showAtLocation(videoView, Gravity.BOTTOM, 0, 0);
	}
	
	/**
	 * 显示播放信息PopupWindow
	 */
	private void showRecordPopupWindow() {
		if (infoPopupWindow != null && videoView.isShown()) {
			infoPopupWindow.setWidth(LayoutParams.WRAP_CONTENT);
			infoPopupWindow.setHeight(LayoutParams.WRAP_CONTENT); 
			infoPopupWindow.setBackgroundDrawable(new BitmapDrawable());
			infoPopupWindow.setOutsideTouchable(false);
			infoPopupWindow.setTouchable(true);
			
			infoPopupWindow.showAtLocation(videoView, Gravity.TOP | Gravity.RIGHT, 60, 60);
			infoPopupWindow.update();
		}
	}
	
	/**
	 * 隐藏PopupWindow
	 */
	private void hidePopupWindow() {
		isPopupWindowShow = false;
		cancelDelayHide();
		if (controlerPopupWindow.isShowing()) {
			controlerPopupWindow.dismiss();
		}
	}
	
	/**
	 * 延迟隐藏控制器
	 */
	private void hidePopupWindowDelay() {
		cancelDelayHide();
		handler.sendEmptyMessageDelayed(HIDE_POPUPWINDOW, SHOW_TIME_MS);
	}
	
	/**
	 * 取消延迟隐藏
	 */
	private void cancelDelayHide() {
		if (handler.hasMessages(HIDE_POPUPWINDOW)) {
			handler.removeMessages(HIDE_POPUPWINDOW);
		}
	}
	
	/**
	 * 获得屏幕尺寸大小
	 */
	private void getScreenSize() {
		Display display = getWindowManager().getDefaultDisplay();
		screenWidth = display.getWidth();
		screenHeight = display.getHeight();
		if (screenHeight > screenWidth) {
			screenWidth = screenHeight;
		}
		bottomHeight = screenHeight / 4;
	}
	
	/**
	 * 自定义Toast显示
	 */
	private static void toastNotify(Context context, String notify_text, int duration) {	
		LayoutInflater Inflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
		View tx_view = Inflater.inflate(R.layout.toast_layout, null);
		
		TextView textView = (TextView)tx_view.findViewById(R.id.toast_text_id);
		textView.setText(notify_text);
		
		Toast toast = new Toast(context);
		toast.setDuration(duration);
		toast.setView(tx_view);
		toast.show();
	}
	
	/**
	 * 自定义Dialog
	 */
	public Dialog createLoadingDialog(String msg) {
		LayoutInflater inflater = LayoutInflater.from(mContext);
		View v = inflater.inflate(R.layout.dialog_player, null);
		LinearLayout layout = (LinearLayout) v.findViewById(R.id.dialog_view);
		ImageView spaceshipImage = (ImageView) v.findViewById(R.id.dialog_img);
		TextView tipTextView = (TextView) v.findViewById(R.id.dialog_textView);
		Animation hyperspaceJumpAnimation = AnimationUtils.loadAnimation(mContext, R.anim.dialog_anim);
		spaceshipImage.startAnimation(hyperspaceJumpAnimation);
		tipTextView.setText(msg);
		Dialog loadingDialog = new Dialog(mContext, R.style.AppThemeFullscreen);
		loadingDialog.setCancelable(true);
		loadingDialog.setCanceledOnTouchOutside(false);
		loadingDialog.setOnCancelListener(new OnCancelListener() {
			@Override
			public void onCancel(DialogInterface dialog) {
				// TODO Auto-generated method stub
				RemoteFilePlayerActivity.this.finish();
			}
		});
		loadingDialog.setContentView(layout, new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.FILL_PARENT,
				LinearLayout.LayoutParams.FILL_PARENT));
		return loadingDialog;
	}
	
	@Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        getScreenSize();
        if (isPopupWindowShow) {
			showPopupWindow();
			hidePopupWindowDelay();
		} else {
			hidePopupWindow();
		}
	}
	
	@Override
	protected void onPause() {
		// TODO Auto-generated method stub
		super.onPause();
		closePlayer();
		RemoteFilePlayerActivity.this.finish();
	}

	@Override
	protected void onStop() {
		// TODO Auto-generated method stub
		super.onStop();
		destroyDialogView();
	}

	@Override
	protected void onDestroy() {
		// TODO Auto-generated method stub
		super.onDestroy();
		//注销广播
		unregisterReceiver(playerReceiver);
		//解除屏幕保持唤醒
		if ((wakeLock != null) && (wakeLock.isHeld())) {
			wakeLock.release(); 
			wakeLock = null;
		}
	}

	/**
	 * 销毁弹出框
	 */
	private void destroyDialogView() {
		if (handler.hasMessages(REQUEST_TIME_OUT)) {
			handler.removeMessages(REQUEST_TIME_OUT);
		}
		if ((mDialog != null) && (mDialog.isShowing())) {
			mDialog.dismiss();
			mDialog = null;
		}
		if (controlerPopupWindow.isShowing()) {
			controlerPopupWindow.dismiss();
		}
		if (infoPopupWindow.isShowing()) {
			infoPopupWindow.dismiss();
		}
	}

	/**
	 * 关闭播放器
	 */
	private void closePlayer() {
		try {
			//关闭实时音视频
			try {
				TunnelCommunication.getInstance().stopRemoteFile(dealerName);
				if (videoView != null) {
					videoView.stopVideo();
				}
				if (audioThread != null) {
					audioThread.stopAudioThread();
					audioThread = null;
				}
			} catch (Exception e) {
				System.out.println("MyDebug: 关闭终端录像音视频对讲异常！");
				e.printStackTrace();
			}
		} catch (Exception e) {
			System.out.println("MyDebug: 关闭终端录像播放器异常！");
			e.printStackTrace();
		}
	}
	
	/**
	 * @author sunfusheng
	 * 播放器的广播接收
	 */
	public class PlayerReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			// TODO Auto-generated method stub
			String action = intent.getAction();
			
			if (action.equals(REQUEST_REMOTE_FILE_PLAYER_ACTION)) {
				// status 0：请求录像播放成功  2：请求录像文件错误  3：播放结束  4：正在播放,返回播放位置和播放速度  5：返回错误的消息
				String peerId = (String) intent.getCharSequenceExtra("peerId");
				if (!peerId.equals(dealerName)) {
					return ;
				}
				int status = intent.getIntExtra("status", 5);
				int position = intent.getIntExtra("position", fileInfo.lastPosition);
				switch (status) {
					case 0: // 请求录像播放成功
						if (handler.hasMessages(REQUEST_TIME_OUT)) {
							handler.removeMessages(REQUEST_TIME_OUT);
						}
						//播放终端录像文件
						if (videoView == null) {
							videoView = new VideoView(mContext);
						}
						videoView.playVideo();
						if (audioThread == null) {
							audioThread = new AudioThread();
						}
						if (!audioThread.isAlive()) {
							audioThread.startAudioThread();
							audioThread.start();
						}
						break;
					case 1: //暂无
						break;
					case 3: // 播放结束
						if (playFrontOrBackFile(false) == 2) {
							RemoteFilePlayerActivity.this.finish();
						}
						break;
					case 4: // 正在播放,返回播放位置和播放速度
						if ((mDialog != null) && (mDialog.isShowing())) {
							mDialog.dismiss();
							mDialog = null;
						}
						fileInfo.lastPosition = position;
						sendHandlerMsg(handler, SEEKBAR_REFRESH, position);
						break;
					case 2: // 请求录像文件错误
					case 5: // 返回错误的消息
						Toast.makeText(mContext, "请求远程录像错误，请重试！", Toast.LENGTH_SHORT).show();
						RemoteFilePlayerActivity.this.finish();
						break;
				}
			}
			else if (action.equals(BackstageService.TUNNEL_REQUEST_ACTION)) {
				String peerId = (String) intent.getCharSequenceExtra("peerId");
				if (!peerId.equals(dealerName)) {
					return ;
				}
				int TunnelEvent = intent.getIntExtra("TunnelEvent", 1);
				switch (TunnelEvent) {
					case 0: // 通道打开
						break;
					case 1: // 通道关闭
						toastNotify(mContext, "对不起，连接意外中断！", Toast.LENGTH_SHORT);
						RemoteFilePlayerActivity.this.finish();
						break;
				}
			}
		}
	}
	
}
