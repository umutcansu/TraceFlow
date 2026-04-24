/**
 * Fixed-capacity ring buffer used to hold pending trace events between
 * flushes. On overflow, oldest events are dropped first — we prefer
 * losing history over blocking the caller.
 */
export class RingBuffer<T> {
  private arr: Array<T | undefined>;
  private head = 0; // next write slot
  private tail = 0; // next read slot
  private size = 0;

  constructor(public readonly capacity: number) {
    if (capacity <= 0) throw new Error("RingBuffer capacity must be positive");
    this.arr = new Array<T | undefined>(capacity);
  }

  push(value: T): void {
    this.arr[this.head] = value;
    this.head = (this.head + 1) % this.capacity;
    if (this.size === this.capacity) {
      // Drop oldest by advancing tail.
      this.tail = (this.tail + 1) % this.capacity;
    } else {
      this.size++;
    }
  }

  /** Remove and return up to `max` oldest items (FIFO). */
  drain(max: number): T[] {
    if (max <= 0 || this.size === 0) return [];
    const n = Math.min(max, this.size);
    const out: T[] = new Array(n);
    for (let i = 0; i < n; i++) {
      out[i] = this.arr[this.tail] as T;
      this.arr[this.tail] = undefined;
      this.tail = (this.tail + 1) % this.capacity;
    }
    this.size -= n;
    return out;
  }

  /** Push a batch back to the front for retry (preserves order). */
  prepend(items: T[]): void {
    // Walk backwards so the first item ends up at the "oldest" position.
    for (let i = items.length - 1; i >= 0; i--) {
      this.tail = (this.tail - 1 + this.capacity) % this.capacity;
      this.arr[this.tail] = items[i];
      if (this.size === this.capacity) {
        // Lose the newest instead so retried batch stays intact.
        this.head = (this.head - 1 + this.capacity) % this.capacity;
      } else {
        this.size++;
      }
    }
  }

  get length(): number {
    return this.size;
  }
}
