package org.pjsip.pjsua2;

public class TestVideoWindow extends VideoWindow {
  private volatile boolean hidden = false;
  private volatile boolean deleted = false;

  public TestVideoWindow() {
    super(0, false);
  }

  @Override
  public void Show(boolean show) {
    if (!show) {
      this.hidden = true;
    }
  }

  @Override
  public synchronized void delete() {
    this.deleted = true;
  }

  public boolean isHidden() {
    return hidden;
  }

  public boolean isDeleted() {
    return deleted;
  }
}
