/*
 * Copyright (C) 2016 Peng fei Pan <sky@xiaopan.me>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.xiaopan.sketch.feature.large;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import java.lang.ref.WeakReference;

import me.xiaopan.sketch.Sketch;

class MainHandler extends Handler {
    private static final String NAME = "MainHandler";

    private static final int WHAT_RECYCLE_DECODE_THREAD = 2001;
    private static final int WHAT_INIT_COMPLETED = 2002;
    private static final int WHAT_INIT_FAILED = 2003;
    private static final int WHAT_DECODE_COMPLETED = 2004;
    private static final int WHAT_DECODE_FAILED = 2005;

    private WeakReference<ImageRegionDecodeExecutor> reference;

    public MainHandler(Looper looper, ImageRegionDecodeExecutor decodeExecutor) {
        super(looper);
        reference = new WeakReference<ImageRegionDecodeExecutor>(decodeExecutor);
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case WHAT_RECYCLE_DECODE_THREAD:
                recycleDecodeThread();
                break;
            case WHAT_INIT_COMPLETED:
                initCompleted((ImageRegionDecoder) msg.obj, msg.arg1);
                break;
            case WHAT_INIT_FAILED:
                initFailed((InitHandler.InitFailedException) msg.obj, msg.arg1);
                break;
            case WHAT_DECODE_COMPLETED:
                decodeCompleted(msg.arg1, (TileDecodeResult) msg.obj);
                break;
            case WHAT_DECODE_FAILED:
                decodeFailed(msg.arg1, (DecodeHandler.DecodeFailedException) msg.obj);
                break;
        }
    }


    /**
     * 延迟三十秒停止解码线程
     */
    public void postDelayRecycleDecodeThread() {
        cancelDelayDestroyThread();

        Message destroyMessage = obtainMessage(MainHandler.WHAT_RECYCLE_DECODE_THREAD);
        sendMessageDelayed(destroyMessage, 30 * 1000);
    }

    private void recycleDecodeThread() {
        ImageRegionDecodeExecutor decodeExecutor = reference.get();
        if (decodeExecutor != null) {
            decodeExecutor.recycleDecodeThread();
        }
    }

    /**
     * 取消停止解码线程的延迟任务
     */
    public void cancelDelayDestroyThread() {
        removeMessages(MainHandler.WHAT_RECYCLE_DECODE_THREAD);
    }


    public void postInitCompleted(ImageRegionDecoder decoder, int initKey) {
        Message message = obtainMessage(MainHandler.WHAT_INIT_COMPLETED);
        message.arg1 = initKey;
        message.obj = decoder;
        message.sendToTarget();
    }

    private void initCompleted(ImageRegionDecoder decoder, int key) {
        ImageRegionDecodeExecutor decodeExecutor = reference.get();
        if (decodeExecutor == null) {
            if (Sketch.isDebugMode()) {
                Log.w(Sketch.TAG, NAME + ". weak reference break. initCompleted. key: " + key + ", imageUri: " + decoder.getImageUri());
            }
            decoder.recycle();
            return;
        }

        int newKey = decodeExecutor.getInitKey();
        if (key != newKey) {
            if (Sketch.isDebugMode()) {
                Log.w(Sketch.TAG, NAME + ". init key expired. initCompleted. key: " + key + ". newKey: " + newKey + ", imageUri: " + decoder.getImageUri());
            }
            decoder.recycle();
            return;
        }

        decodeExecutor.initCompleted(decoder);
    }


    public void postInitFailed(InitHandler.InitFailedException e, int key) {
        Message message = obtainMessage(MainHandler.WHAT_INIT_FAILED);
        message.arg1 = key;
        message.obj = e;
        message.sendToTarget();
    }

    private void initFailed(InitHandler.InitFailedException e, int key) {
        ImageRegionDecodeExecutor decodeExecutor = reference.get();
        if (decodeExecutor == null) {
            if (Sketch.isDebugMode()) {
                Log.w(Sketch.TAG, NAME + ". weak reference break. initFailed. key: " + key + ", imageUri: " + e.getImageUri());
            }
            return;
        }

        int newKey = decodeExecutor.getInitKey();
        if (key != newKey) {
            if (Sketch.isDebugMode()) {
                Log.w(Sketch.TAG, NAME + ". key expire. initFailed. key: " + key + ". newKey: " + newKey + ", imageUri: " + e.getImageUri());
            }
            return;
        }

        decodeExecutor.initFailed(e.getException());
    }


    public void postDecodeCompleted(int key, Tile tile, Bitmap bitmap) {
        Message message = obtainMessage(MainHandler.WHAT_DECODE_COMPLETED);
        message.arg1 = key;
        message.obj = new TileDecodeResult(bitmap, tile);
        message.sendToTarget();
    }

    private void decodeCompleted(int key, TileDecodeResult result) {
        ImageRegionDecodeExecutor decodeExecutor = reference.get();
        if (decodeExecutor == null) {
            if (Sketch.isDebugMode()) {
                Log.w(Sketch.TAG, NAME + ". weak reference break. decodeCompleted. key: " + key +", tile=" + result.tile.getInfo());
            }
            result.bitmap.recycle();
            return;
        }

        if (!result.tile.isExpire(key)) {
            decodeExecutor.decodeCompleted(result.tile, result.bitmap);
        } else {
            result.bitmap.recycle();
            decodeExecutor.decodeFailed(result.tile, new DecodeHandler.DecodeFailedException(result.tile,
                    DecodeHandler.DecodeFailedException.CAUSE_CALLBACK_KEY_EXPIRED));
        }
    }

    public void postDecodeFailed(int key, DecodeHandler.DecodeFailedException exception) {
        Message message = obtainMessage(MainHandler.WHAT_DECODE_FAILED);
        message.arg1 = key;
        message.obj = exception;
        message.sendToTarget();
    }

    private void decodeFailed(int key, DecodeHandler.DecodeFailedException exception) {
        ImageRegionDecodeExecutor decodeExecutor = reference.get();
        if (decodeExecutor == null) {
            if (Sketch.isDebugMode()) {
                Log.w(Sketch.TAG, NAME + ". weak reference break. decodeFailed. key: " + key +", tile=" + exception.tile.getInfo());
            }
            return;
        }

        decodeExecutor.decodeFailed(exception.tile, exception);
    }


    public void clean(String why) {
        if (Sketch.isDebugMode()) {
            Log.w(Sketch.TAG, NAME + ". clean. " + why);
        }

        removeMessages(WHAT_RECYCLE_DECODE_THREAD);
        removeMessages(WHAT_INIT_COMPLETED);
        removeMessages(WHAT_INIT_FAILED);
        removeMessages(WHAT_DECODE_COMPLETED);
        removeMessages(WHAT_DECODE_FAILED);
    }

    public static final class TileDecodeResult {
        public Tile tile;
        public Bitmap bitmap;

        public TileDecodeResult(Bitmap bitmap, Tile tile) {
            this.bitmap = bitmap;
            this.tile = tile;
        }
    }
}
