/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.source.hls.playlist;

import com.google.android.exoplayer2.C;

import java.util.List;

/**
 * Represents an HLS media playlist.
 */
public final class HlsMediaPlaylist extends HlsPlaylist {

  /**
   * Media segment reference.
   */
  public static final class Segment implements Comparable<Long> {

    public final String url;
    public final double durationSecs;
    public final int discontinuitySequenceNumber;
    public final long startTimeUs;
    public final boolean isEncrypted;
    public final String encryptionKeyUri;
    public final String encryptionIV;
    public final long byterangeOffset;
    public final long byterangeLength;

    public Segment(String uri, double durationSecs, int discontinuitySequenceNumber,
        long startTimeUs, boolean isEncrypted, String encryptionKeyUri, String encryptionIV,
        long byterangeOffset, long byterangeLength) {
      this.url = uri;
      this.durationSecs = durationSecs;
      this.discontinuitySequenceNumber = discontinuitySequenceNumber;
      this.startTimeUs = startTimeUs;
      this.isEncrypted = isEncrypted;
      this.encryptionKeyUri = encryptionKeyUri;
      this.encryptionIV = encryptionIV;
      this.byterangeOffset = byterangeOffset;
      this.byterangeLength = byterangeLength;
    }

    @Override
    public int compareTo(Long startTimeUs) {
      return this.startTimeUs > startTimeUs ? 1 : (this.startTimeUs < startTimeUs ? -1 : 0);
    }
  }

  public static final String ENCRYPTION_METHOD_NONE = "NONE";
  public static final String ENCRYPTION_METHOD_AES_128 = "AES-128";

  public final int mediaSequence;
  public final int targetDurationSecs;
  public final int version;
  public final List<Segment> segments;
  public final boolean live;
  public final boolean liveEvent;
  public final long durationUs;

  public HlsMediaPlaylist(String baseUri, int mediaSequence, int targetDurationSecs, int version,
      boolean live, boolean liveEvent, List<Segment> segments) {
    super(baseUri, HlsPlaylist.TYPE_MEDIA);
    this.mediaSequence = mediaSequence;
    this.targetDurationSecs = targetDurationSecs;
    this.version = version;
    this.live = live;
    this.liveEvent = liveEvent;
    this.segments = segments;

    if (!segments.isEmpty()) {
      Segment last = segments.get(segments.size() - 1);
      durationUs = last.startTimeUs + (long) (last.durationSecs * C.MICROS_PER_SECOND);
    } else {
      durationUs = 0;
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    HlsMediaPlaylist that = (HlsMediaPlaylist) o;

    if (mediaSequence != that.mediaSequence) return false;
    if (targetDurationSecs != that.targetDurationSecs) return false;
    if (version != that.version) return false;
    if (live != that.live) return false;
    if (liveEvent != that.liveEvent) return false;
    if (durationUs != that.durationUs) return false;
    return segments.equals(that.segments);

  }

  @Override
  public int hashCode() {
    int result = mediaSequence;
    result = 31 * result + targetDurationSecs;
    result = 31 * result + version;
    result = 31 * result + segments.hashCode();
    result = 31 * result + (live ? 1 : 0);
    result = 31 * result + (liveEvent ? 1 : 0);
    result = 31 * result + (int) (durationUs ^ (durationUs >>> 32));
    return result;
  }
}
