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

  CallUiCoordinator(Host host) {
    this.host = host;
  }

  boolean isBusy() {
    return callPreviews.isBusy();
  }

  CheckBox cameraToggleBox() {
    if (cameraToggleBox != null) {
      return cameraToggleBox;
    }
    SipService sip = host.sip();
    boolean cameraOn = sip.isCameraEnabled();
    cameraToggleBox = new CheckBox(cameraOn ? "摄像头: 已开启" : "摄像头: 已禁用");
    cameraToggleBox.setSelected(cameraOn);
    cameraToggleBox.getStyleClass().setAll("camera-toggle-btn", cameraOn ? "camera-toggle-on" : "camera-toggle-off");
    cameraToggleBox.setOnAction(
        e -> {
          if (callPreviews.isBusy()) {
            cameraToggleBox.setSelected(sip.isCameraEnabled());
            return;
          }
          boolean enabled = cameraToggleBox.isSelected();
          callPreviewErrorLogged = false;
          sip.setCameraEnabled(enabled);
          cameraToggleBox.setText(enabled ? "摄像头: 已开启" : "摄像头: 已禁用");
          cameraToggleBox.getStyleClass().setAll("camera-toggle-btn", enabled ? "camera-toggle-on" : "camera-toggle-off");
          String remote = sip.isCallActive() ? sip.getActiveCallRemoteUri() : "";
          String mySip = host.user();
          VideoCallWindow expectedWindow = videoCallWindow;
          callPreviews.changeCamera(
              sip,
              expectedWindow,
              enabled,
              () -> {
                if (callPreviews.isBusy()) {
                  return;
                }
                if (videoCallWindow == expectedWindow && expectedWindow != null) {
                  expectedWindow.setMediaBusy(false);
                }
                if (!enabled) {
                  callPreviewStarted = false;
                }
                if (!remote.isBlank()) {
                  if (MainApp.isRoomTarget(remote)) {
                    final boolean cameraEnabled = enabled;
                    roomCameraUpdates.execute(() -> {
                      boolean applied = host.http().toggleRoomVmute(remote, mySip, !cameraEnabled);
                      Platform.runLater(() -> {
                        LogView logView = host.logs();
                        if (logView != null) {
                          logView.append(applied ? "通话" : "警告", applied
                              ? (cameraEnabled ? "MCU 已恢复实时视频" : "MCU 已切换为个人头像")
                              : "本地摄像头状态已更新，MCU 同步失败");
                        }
                      });
                    });
                  } else {
                    sendAvatarControl(!enabled);
                  }
                }
                // Do not call back into MainApp.refreshButtons here: the native
                // preview worker may still hold the SipService monitor.
              },
              error -> {
                if (videoCallWindow == expectedWindow && expectedWindow != null) {
                  expectedWindow.setMediaBusy(false);
                }
                LogView logView = host.logs();
                if (logView != null) {
                  logView.append("警告", "摄像头切换失败：" + error.getMessage());
                }
              });
        });
    return cameraToggleBox;
  }

  void sync() {
    SipService sip = host.sip();
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
              host.runAction(sip::hangup);
            },
            muted -> {
              sip.setAudioMuted(muted);
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

      long remote = sip.getActiveVideoWindowHandle();
      if (connected && !showAvatar && remote != 0) {
        long pip = 0;
        if (cameraOn && !inRoom) {
          if (!sip.isPreviewActive() && !callPreviews.isBusy()) {
            if (!callPreviewErrorLogged) {
              VideoCallWindow expectedWindow = videoCallWindow;
              callPreviews.start(
                  sip,
                  expectedWindow,
                  () -> {
                    if (videoCallWindow == expectedWindow) {
                      expectedWindow.setMediaBusy(false);
                      callPreviewStarted = true;
                      if (!callPreviews.isBusy()) {
                        host.refreshMainUi();
                      }
                    }
                  },
                  error -> {
                    if (videoCallWindow == expectedWindow) {
                      expectedWindow.setMediaBusy(false);
                      callPreviewErrorLogged = true;
                      LogView logView = host.logs();
                      if (logView != null) {
                        logView.append("警告", "本地小窗预览启动失败: " + error.getMessage());
                      }
                      if (!callPreviews.isBusy()) {
                        host.refreshMainUi();
                      }
                    }
                  });
              return;
            }
          } else {
            callPreviewStarted = true;
          }
          pip = sip.getPreviewWindowHandle();
        } else {
          videoCallWindow.detachPip();
          if (callPreviewStarted && sip.isPreviewActive()) {
            sip.stopPreview();
          }
          callPreviewStarted = false;
        }
        videoCallWindow.attachNative(remote, pip);
        startPipLayout();
      } else {
        if (showAvatar) {
          videoCallWindow.detachPip();
          if (callPreviewStarted && sip.isPreviewActive()) {
            sip.stopPreview();
          }
          callPreviewStarted = false;
        }
        stopPipLayout();
      }
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
            () -> host.runAction(sip::hangup),
            muted -> {
              sip.setAudioMuted(muted);
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
      if (callPreviewStarted && sip.isPreviewActive()) {
        sip.stopPreview();
      }
      callPreviewStarted = false;
      stopPipLayout();
    }
  }

  void setRemoteAvatarOff(boolean off, String fromUri) {
    remoteAvatarOff = off;
    if (!callPreviews.isBusy() && videoCallWindow != null && videoCallWindow.isShowing()) {
      videoCallWindow.setRemoteAvatarOff(off, MainApp.extractUser(fromUri));
    }
  }

  void answer(boolean video) {
    incomingAnswered = true;
    closeIncomingWindow();
    host.runAction(() -> host.sip().answer(video));
    Platform.runLater(this::sync);
  }

  void reject() {
    incomingAnswered = false;
    closeIncomingWindow();
    host.runAction(host.sip()::hangup);
    Platform.runLater(this::sync);
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
    if (!callPreviews.isBusy() && callPreviewStarted && host.sip().isPreviewActive()) {
      host.sip().stopPreview();
    }
    callPreviewStarted = false;
    callPreviewErrorLogged = false;
  }

  void sendAvatarControl(boolean off) {
    SipService sip = host.sip();
    String remote = sip.getActiveCallRemoteUri();
    String peer = MainApp.extractUser(remote);
    if (peer.isBlank() || peer.startsWith("room-") || !sip.isCurrentCallVideo()) {
      return;
    }
    String body = off ? SipService.VIDEO_AVATAR_OFF : SipService.VIDEO_AVATAR_ON;
    host.runAction(
        () -> {
          try {
            sip.sendMessage(peer, host.domain(), body);
          } catch (Exception ex) {
            LogView logView = host.logs();
            if (logView != null) {
              logView.append("警告", "发送摄像头头像状态失败: " + ex.getMessage());
            }
          }
        });
  }

  void setCallCamera(boolean enabled) {
    if (cameraToggleBox == null) {
      host.sip().setCameraEnabled(enabled);
      return;
    }
    if (cameraToggleBox.isSelected() != enabled) {
      cameraToggleBox.fire();
    }
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
                  long remote = host.sip().getActiveVideoWindowHandle();
                  long pip = host.sip().isPreviewActive() ? host.sip().getPreviewWindowHandle() : 0;
                  if (remote != 0) {
                    videoCallWindow.attachNative(remote, pip);
                  }
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

  @Override
  public void close() {
    roomCameraUpdates.shutdownNow();
    callPreviews.close();
    closeWindows();
  }
}
