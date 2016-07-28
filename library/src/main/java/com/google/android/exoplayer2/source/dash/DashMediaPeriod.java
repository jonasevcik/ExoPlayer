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
package com.google.android.exoplayer2.source.dash;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.source.AdaptiveMediaSourceEventListener.EventDispatcher;
import com.google.android.exoplayer2.source.CompositeSequenceableLoader;
import com.google.android.exoplayer2.source.MediaPeriod;
import com.google.android.exoplayer2.source.SampleStream;
import com.google.android.exoplayer2.source.SequenceableLoader;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.chunk.ChunkSampleStream;
import com.google.android.exoplayer2.source.dash.manifest.AdaptationSet;
import com.google.android.exoplayer2.source.dash.manifest.DashManifest;
import com.google.android.exoplayer2.source.dash.manifest.Period;
import com.google.android.exoplayer2.source.dash.manifest.Representation;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.upstream.Allocator;
import com.google.android.exoplayer2.upstream.LoaderErrorThrower;

import java.io.IOException;
import java.util.List;

/**
 * A DASH {@link MediaPeriod}.
 */
/* package */ final class DashMediaPeriod implements MediaPeriod,
    SequenceableLoader.Callback<ChunkSampleStream<DashChunkSource>> {

  private final DashChunkSource.Factory chunkSourceFactory;
  private final int minLoadableRetryCount;
  private final EventDispatcher eventDispatcher;
  private final long elapsedRealtimeOffset;
  private final LoaderErrorThrower manifestLoaderErrorThrower;
  private final TrackGroupArray trackGroups;

  private ChunkSampleStream<DashChunkSource>[] sampleStreams;
  private CompositeSequenceableLoader sequenceableLoader;
  private Callback callback;
  private Allocator allocator;
  private DashManifest manifest;
  private long durationUs;
  private int index;
  private Period period;

  public DashMediaPeriod(DashManifest manifest, int index,
      DashChunkSource.Factory chunkSourceFactory,  int minLoadableRetryCount,
      EventDispatcher eventDispatcher, long elapsedRealtimeOffset,
      LoaderErrorThrower manifestLoaderErrorThrower) {
    this.manifest = manifest;
    this.index = index;
    this.chunkSourceFactory = chunkSourceFactory;
    this.minLoadableRetryCount = minLoadableRetryCount;
    this.eventDispatcher = eventDispatcher;
    this.elapsedRealtimeOffset = elapsedRealtimeOffset;
    this.manifestLoaderErrorThrower = manifestLoaderErrorThrower;
    durationUs = manifest.dynamic ? C.UNSET_TIME_US : manifest.getPeriodDuration(index) * 1000;
    period = manifest.getPeriod(index);
    trackGroups = buildTrackGroups(period);
  }

  public void updateManifest(DashManifest manifest, int index) {
    this.manifest = manifest;
    this.index = index;
    durationUs = manifest.dynamic ? C.UNSET_TIME_US : manifest.getPeriodDuration(index) * 1000;
    period = manifest.getPeriod(index);
    if (sampleStreams != null) {
      for (ChunkSampleStream<DashChunkSource> sampleStream : sampleStreams) {
        sampleStream.getChunkSource().updateManifest(manifest, index);
      }
      callback.onContinueLoadingRequested(this);
    }
  }

  public long getStartUs() {
    return period.startMs * 1000;
  }

  // MediaPeriod implementation.

  @Override
  public void preparePeriod(Callback callback, Allocator allocator, long positionUs) {
    this.callback = callback;
    this.allocator = allocator;
    sampleStreams = newSampleStreamArray(0);
    sequenceableLoader = new CompositeSequenceableLoader(sampleStreams);
    callback.onPeriodPrepared(this);
  }

  @Override
  public void maybeThrowPrepareError() throws IOException {
    manifestLoaderErrorThrower.maybeThrowError();
  }

  @Override
  public long getDurationUs() {
    return durationUs;
  }

  @Override
  public TrackGroupArray getTrackGroups() {
    return trackGroups;
  }

  @Override
  public SampleStream[] selectTracks(List<SampleStream> oldStreams,
      List<TrackSelection> newSelections, long positionUs) {
    int newEnabledSourceCount = sampleStreams.length + newSelections.size() - oldStreams.size();
    ChunkSampleStream<DashChunkSource>[] newSampleStreams =
        newSampleStreamArray(newEnabledSourceCount);
    int newEnabledSourceIndex = 0;

    // Iterate over currently enabled streams, either releasing them or adding them to the new
    // list.
    for (ChunkSampleStream<DashChunkSource> sampleStream : sampleStreams) {
      if (oldStreams.contains(sampleStream)) {
        sampleStream.release();
      } else {
        newSampleStreams[newEnabledSourceIndex++] = sampleStream;
      }
    }

    // Instantiate and return new streams.
    SampleStream[] streamsToReturn = new SampleStream[newSelections.size()];
    for (int i = 0; i < newSelections.size(); i++) {
      newSampleStreams[newEnabledSourceIndex] =
          buildSampleStream(newSelections.get(i), positionUs);
      streamsToReturn[i] = newSampleStreams[newEnabledSourceIndex];
      newEnabledSourceIndex++;
    }

    sampleStreams = newSampleStreams;
    sequenceableLoader = new CompositeSequenceableLoader(sampleStreams);
    return streamsToReturn;
  }

  @Override
  public boolean continueLoading(long positionUs) {
    return sequenceableLoader.continueLoading(positionUs);
  }

  @Override
  public long getNextLoadPositionUs() {
    return sequenceableLoader.getNextLoadPositionUs();
  }

  @Override
  public long readDiscontinuity() {
    return C.UNSET_TIME_US;
  }

  @Override
  public long getBufferedPositionUs() {
    long bufferedPositionUs = Long.MAX_VALUE;
    for (ChunkSampleStream<DashChunkSource> sampleStream : sampleStreams) {
      long rendererBufferedPositionUs = sampleStream.getBufferedPositionUs();
      if (rendererBufferedPositionUs != C.END_OF_SOURCE_US) {
        bufferedPositionUs = Math.min(bufferedPositionUs, rendererBufferedPositionUs);
      }
    }
    return bufferedPositionUs == Long.MAX_VALUE ? C.END_OF_SOURCE_US : bufferedPositionUs;
  }

  @Override
  public long seekToUs(long positionUs) {
    for (ChunkSampleStream<DashChunkSource> sampleStream : sampleStreams) {
      sampleStream.seekToUs(positionUs);
    }
    return positionUs;
  }

  @Override
  public void releasePeriod() {
    if (sampleStreams != null) {
      for (ChunkSampleStream<DashChunkSource> sampleStream : sampleStreams) {
        sampleStream.release();
      }
      sampleStreams = null;
    }
    sequenceableLoader = null;
    callback = null;
    allocator = null;
  }

  // SequenceableLoader.Callback implementation.

  @Override
  public void onContinueLoadingRequested(ChunkSampleStream<DashChunkSource> sampleStream) {
    callback.onContinueLoadingRequested(this);
  }

  // Internal methods.

  private static TrackGroupArray buildTrackGroups(Period period) {
    TrackGroup[] trackGroupArray = new TrackGroup[period.adaptationSets.size()];
    for (int i = 0; i < period.adaptationSets.size(); i++) {
      AdaptationSet adaptationSet = period.adaptationSets.get(i);
      List<Representation> representations = adaptationSet.representations;
      Format[] formats = new Format[representations.size()];
      for (int j = 0; j < formats.length; j++) {
        formats[j] = representations.get(j).format;
      }
      trackGroupArray[i] = new TrackGroup(formats);
    }
    return new TrackGroupArray(trackGroupArray);
  }

  private ChunkSampleStream<DashChunkSource> buildSampleStream(TrackSelection selection,
      long positionUs) {
    int adaptationSetIndex = trackGroups.indexOf(selection.getTrackGroup());
    AdaptationSet adaptationSet = period.adaptationSets.get(adaptationSetIndex);
    DashChunkSource chunkSource = chunkSourceFactory.createDashChunkSource(
        manifestLoaderErrorThrower, manifest, index, adaptationSetIndex, selection,
        elapsedRealtimeOffset);
    return new ChunkSampleStream<>(adaptationSet.type, chunkSource, this, allocator, positionUs,
        minLoadableRetryCount, eventDispatcher);
  }

  @SuppressWarnings("unchecked")
  private static ChunkSampleStream<DashChunkSource>[] newSampleStreamArray(int length) {
    return new ChunkSampleStream[length];
  }

}
