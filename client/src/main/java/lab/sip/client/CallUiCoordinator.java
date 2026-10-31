package lab.sip.client;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.control.CheckBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the call surfaces (incoming, voice and video) and the local preview
 * lifecycle. MainApp remains the application shell and supplies the few UI and
 * SIP services this module needs through {@link Host}.
 */
final class CallUiCoordinator implements AutoCloseable {
  interface Host {
    Stage owner();

    SipService sip();

    LogView logs();

    MainViewAssembler assembler();

    HttpFileClient http();

    String domain();

    String user();

    void runAction(Throwing action);

    void refreshMainUi();

    /** Update cached UI state only; never query the native engine from this callback. */
    default void cameraStateChanged(boolean enabled) {}

    @FunctionalInterface
    interface Throwing {
      void run() throws Exception;
    }
  }

  private final Host host;
  private CallWindow callWindow;
  private VideoCallWindow videoCallWindow;
  private IncomingCallWindow incomingCallWindow;
  private CheckBox cameraToggleBox;
  private boolean incomingAnswered;
  private boolean remoteAvatarOff;
  private Object presentedCall;
  private Timeline pipLayoutTimeline;
  private boolean callPreviewErrorLogged;
  private boolean callPreviewStarted;
  private final CallPreviewLifecycle callPreviews = new CallPreviewLifecycle();
  private final ExecutorService roomCameraUpdates =
      Executors.newSingleThreadExecutor(
          task -> {
            Thread thread = new Thread(task, "call-room-camera");
            thread.setDaemon(true);
            return thread;
          });
  private final ExecutorService sipControl =
      Executors.newSingleThreadExecutor(
          task -> {
            Thread thread = new Thread(task, "call-sip-control");
            thread.setDaemon(true);
            return thread;
          });

  CallUiCoordinator(Host host) {
    this.host = host;
  }

  boolean isBusy() {
    return callPreviews.isBusy();
  }

  CheckBox cameraToggleBox() {
    if (cameraToggleBox == null) {
      cameraToggleBox = new CheckBox();
      cameraToggleBox.setOnAction(e -> setCallCamera(cameraToggleBox.isSelected()));
      updateCameraUi();
    }
    return cameraToggleBox;
  }

  private void updateCameraUi() {
    boolean enabled = host.sip().isCameraEnabled();
    host.cameraStateChanged(enabled);
    if (cameraToggleBox != null) {
      cameraToggleBox.setSelected(enabled);
      cameraToggleBox.setDisable(callPreviews.isBusy());
      cameraToggleBox.setText(enabled ? "摄像头: 已开启" : "摄像头: 已禁用");
      cameraToggleBox.getStyleClass().setAll("camera-toggle-btn", enabled ? "camera-toggle-on" : "camera-toggle-off");
    }
    if (videoCallWindow != null) {
      videoCallWindow.setCameraState(enabled);
      videoCallWindow.setMediaBusy(callPreviews.isBusy());
    }
  }

  void sync() {
    SipService sip = host.sip();
    Object current = sip.callIdentity();
    if (current != presentedCall) { remoteAvatarOff = false; presentedCall = current; }
    updateCameraUi();
    boolean inCall = sip.isInCall();
    boolean connected = sip.isCallConnected();
    boolean incoming = sip.hasIncoming();

    if (!inCall) {
      closeIncomingWindow();
      incomingAnswered = false;
      closeWindows();
      return;
    }

    if (incoming && !connected && !incomingAnswered) {
      if (videoCallWindow != null) {
        videoCallWindow.close();
        videoCallWindow = null;
      }
      if (callWindow != null) {
        callWindow.close();
        callWindow = null;
      }
      String incomingId = MainApp.extractUser(sip.currentCallTarget());
      String incomingTarget = callDisplayName(incomingId);
      if (incomingTarget.isBlank()) {
        incomingTarget = "来电";
      }
      if (incomingCallWindow == null) {
        incomingCallWindow = new IncomingCallWindow(host.owner());
      }
      incomingCallWindow.show(
          incomingTarget,
          sip.isIncomingVideo(),
          () -> answer(true),
          () -> answer(false),
          this::reject);
      incomingCallWindow.setAvatar(callAvatar(incomingId));
      return;
    }

    closeIncomingWindow();
    if (connected) {
      incomingAnswered = false;
    }
    if (!connected && sip.hasIncoming()) {
      closeWindows();
      return;
    }

    String targetId = MainApp.extractUser(sip.currentCallTarget());
    String target = callDisplayName(targetId);
    if (target.isBlank()) {
      target = "通话";
    }
    boolean video = sip.isCurrentCallVideoIntent();
    boolean videoActive = sip.isCurrentCallVideo();
    boolean inRoom = sip.isInRoomCall();
    boolean cameraOn = sip.isCameraEnabled();

    if (video) {
      if (callWindow != null) {
        callWindow.close();
        callWindow = null;
      }
      if (videoCallWindow == null) {
        videoCallWindow = new VideoCallWindow(host.owner());
      }
      if (videoCallWindow.isHangingUp()) {
        return;
      }
      if (!videoCallWindow.isShowing()) {
        videoCallWindow.show(
            target,
            cameraOn,
            connected,
            inRoom,
            () -> {
              if (videoCallWindow != null) {
                videoCallWindow.hideForHangup();
              }
              runSip(sip::hangup);
            },
            muted -> {
              runSip(() -> sip.setAudioMuted(muted));
              if (videoCallWindow != null) {
                videoCallWindow.setMuted(sip.isAudioMuted());
              }
            },
            this::setCallCamera);
      } else {
        videoCallWindow.update(target, cameraOn, connected);
      }
      videoCallWindow.setMuted(sip.isAudioMuted());
      videoCallWindow.setAvatar(callAvatar(targetId));
      boolean showAvatar = connected && (remoteAvatarOff || !videoActive);
      videoCallWindow.setAvatarHint(
          remoteAvatarOff
              ? "对方已关闭摄像头，仍可继续语音交流"
              : "对方正在使用语音，视频画面暂不可用");
      videoCallWindow.setRemoteAvatarOff(showAvatar, target);

      long remote = showAvatar ? 0 : sip.getActiveVideoWindowHandle();
      if (connected) {
        // Local preview is independent of whether the peer is currently sending video.
        if (cameraOn && !inRoom && !sip.isPreviewActive() && !callPreviews.isBusy() && !callPreviewErrorLogged) {
          VideoCallWindow expected = videoCallWindow;
          callPreviews.start(sip, expected, () -> {
            if (videoCallWindow == expected) { callPreviewStarted = true; updateCameraUi(); sync(); }
          }, error -> {
            callPreviewErrorLogged = true; updateCameraUi(); logFailure("本地预览未开启：" + error.getMessage());
          });
        } else if (!cameraOn || inRoom) {
          videoCallWindow.detachPip(); stopCallPreview();
        } else if (sip.isPreviewActive()) callPreviewStarted = true;
        videoCallWindow.attachNative(remote, cameraOn && !inRoom ? sip.getPreviewWindowHandle() : 0);
        startPipLayout();
      } else stopPipLayout();
    } else {
      if (videoCallWindow != null) {
        videoCallWindow.close();
        videoCallWindow = null;
      }
      if (callWindow == null) {
        callWindow = new CallWindow(host.owner());
      }
      if (!callWindow.isShowing()) {
        callWindow.show(
            target,
            false,
            cameraOn,
            connected,
            () -> runSip(sip::hangup),
            muted -> {
              runSip(() -> sip.setAudioMuted(muted));
              if (callWindow != null) {
                callWindow.setMuted(muted);
              }
            },
            this::setCallCamera);
      } else {
        callWindow.update(target, false, cameraOn, connected);
      }
      callWindow.setMuted(sip.isAudioMuted());
      callWindow.setAvatar(callAvatar(targetId));
      stopCallPreview();
      stopPipLayout();
    }
  }

  void setRemoteAvatarOff(boolean off, String fromUri) {
    if (!host.sip().isCallConnected() || !MainApp.extractUser(host.sip().currentCallTarget())
        .equalsIgnoreCase(MainApp.extractUser(fromUri))) return;
    remoteAvatarOff = off;
    if (!callPreviews.isBusy() && videoCallWindow != null && videoCallWindow.isShowing()) {
      videoCallWindow.setRemoteAvatarOff(off, MainApp.extractUser(fromUri));
    }
  }

  void answer(boolean video) {
    incomingAnswered = true;
    closeIncomingWindow();
    runSip(() -> {
      host.sip().answer(video);
      Platform.runLater(this::sync);
    });
  }

  void reject() {
    incomingAnswered = false;
    closeIncomingWindow();
    runSip(() -> {
      host.sip().hangup();
      Platform.runLater(this::sync);
    });
  }

  void closeIncomingWindow() {
    if (incomingCallWindow != null) {
      incomingCallWindow.close();
      incomingCallWindow = null;
    }
  }

  void closeWindows() {
    remoteAvatarOff = false;
    if (videoCallWindow != null) {
      videoCallWindow.closeWithoutDetach();
      videoCallWindow = null;
    }
    if (callWindow != null) {
      callWindow.close();
      callWindow = null;
    }
    stopPipLayout();
    stopCallPreview();
    callPreviewErrorLogged = false;
  }

  void sendAvatarControl(boolean off) { host.sip().publishCameraState(); }

  void setCallCamera(boolean enabled) {
    if (callPreviews.isBusy()) { updateCameraUi(); return; }
    SipService sip = host.sip();
    Object call = sip.callIdentity();
    boolean previous = sip.isCameraEnabled();
    sip.setCameraEnabled(enabled);
    String remote = sip.currentCallTarget();
    String user = host.user();
    callPreviewErrorLogged = false;
    callPreviews.changeCamera(sip, videoCallWindow, enabled, () -> {
      if (sip.callIdentity() != call) return;
      if (!enabled) callPreviewStarted = false;
      updateCameraUi();
      if (MainApp.isRoomTarget(remote)) roomCameraUpdates.execute(() -> {
        boolean applied = host.http().toggleRoomVmute(remote, user, !enabled);
        if (!applied) Platform.runLater(() -> logFailure("本地摄像头已更新，MCU 头像同步失败"));
      });
      sip.publishCameraState();
      sync();
    }, failure -> {
      sip.setCameraEnabled(previous);
      updateCameraUi();
      logFailure("摄像头切换失败：" + failure.getMessage());
      if (!callPreviews.isBusy()) sync();
    });
    updateCameraUi();
  }

  private void logFailure(String text) {
    if (host.logs() != null) host.logs().append("警告", text);
  }

  private void stopCallPreview() {
    if (!callPreviewStarted || !host.sip().isPreviewActive() || callPreviews.isBusy()) return;
    callPreviewStarted = false;
    callPreviews.execute(host.sip()::stopPreview, this::updateCameraUi, error -> logFailure(error.getMessage()));
  }

  private void startPipLayout() {
    if (pipLayoutTimeline != null) {
      return;
    }
    pipLayoutTimeline =
        new Timeline(
            new KeyFrame(
                Duration.millis(500),
                e -> {
                  if (callPreviews.isBusy()) {
                    return;
                  }
                  if (videoCallWindow == null || !host.sip().isCallConnected()) {
                    return;
                  }
                  long remote = remoteAvatarOff ? 0 : host.sip().getActiveVideoWindowHandle();
                  long pip = host.sip().isPreviewActive() ? host.sip().getPreviewWindowHandle() : 0;
                  videoCallWindow.attachNative(remote, pip);
                }));
    pipLayoutTimeline.setCycleCount(Timeline.INDEFINITE);
    pipLayoutTimeline.play();
  }

  private void stopPipLayout() {
    if (pipLayoutTimeline != null) {
      pipLayoutTimeline.stop();
      pipLayoutTimeline = null;
    }
  }

  private String callDisplayName(String targetId) {
    MainViewAssembler assembler = host.assembler();
    var contact = assembler == null ? null : assembler.getContactCard(targetId);
    return contact == null || contact.nameLabel.getText().isBlank() ? targetId : contact.nameLabel.getText();
  }

  private javafx.scene.image.Image callAvatar(String targetId) {
    MainViewAssembler assembler = host.assembler();
    var contact = assembler == null ? null : assembler.getContactCard(targetId);
    return contact == null ? null : contact.contactImgView.getImage();
  }

  private void runSip(Host.Throwing action) {
    sipControl.execute(
        () -> {
          try {
            action.run();
          } catch (Throwable failure) {
            Platform.runLater(
                () -> {
                  LogView logView = host.logs();
                  if (logView != null) {
                    logView.append("错误", failure.getClass().getSimpleName() + ": " + failure.getMessage());
                  }
                });
          }
        });
  }

  @Override
  public void close() {
    sipControl.shutdownNow();
    roomCameraUpdates.shutdownNow();
    closeWindows();
    callPreviews.close();
  }
}
