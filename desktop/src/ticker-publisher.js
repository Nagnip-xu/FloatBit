// Keep receiving every trade; only sample the latest cache for the display.
class TickerPublisher {
  constructor(publish, { interval = 1000, now = Date.now, schedule = setTimeout, cancel = clearTimeout } = {}) {
    Object.assign(this, { publish, interval, now, scheduleTimer: schedule, cancelTimer: cancel });
    this.lastPublished = -Infinity;
    this.timer = null;
    this.stopped = false;
  }
  schedule() {
    if (this.stopped || this.timer !== null) return;
    const remaining = this.interval - (this.now() - this.lastPublished);
    if (remaining <= 0) this.flush();
    else this.timer = this.scheduleTimer(() => this.flush(), remaining);
  }
  flush() {
    this.timer = null;
    if (this.stopped) return;
    this.lastPublished = this.now();
    this.publish();
  }
  stop() {
    this.stopped = true;
    if (this.timer !== null) this.cancelTimer(this.timer);
    this.timer = null;
  }
}
module.exports = { TickerPublisher };
