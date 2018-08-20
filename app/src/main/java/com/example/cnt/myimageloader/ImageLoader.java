package com.example.cnt.myimageloader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.support.v4.util.LruCache;
import android.util.Log;
import android.widget.ImageView;

import com.jakewharton.disklrucache.DiskLruCache;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 懒得写直接网上找到和书里一模一样的代码再来看，打一篇笔记而已
 *
 * 图片加载，使用类方法build()来创建实例，
 * 调用public void bindBitMap(final String uri, final ImageView imageView, final int reqWidth, final int reqHeight) 来异步调用，
 *
 * 内部封装了图片压缩，图片的 LruCache 和 DiskLruCache 缓存，网络的拉取，handler 所控制的ui交互，利用线程池控制的异步加载
 *
 * 使用时要获取网络和内存读写的权限，还有DiskLruCache库的导入implementation 'com.jakewharton:disklrucache:2.0.2'
 * Created by cnt on 2018/8/18.
 */

public class ImageLoader {

    private static final String TAG = "ImageLoader";
    public static final int MESSAGE_POST_RESULT = 1;
    //这里的tag是随意的；
    private static final int TAG_KEY_URI = 70;

    private Context mContext;
    //图片压缩的 ImageResizeer实现
    private ImageResizeer mImageResizeer = new ImageResizeer();
    private LruCache<String, Bitmap> mMemoryCache;
    private DiskLruCache mDiskLruCache;

    private ImageLoader(Context context) {
        mContext = context.getApplicationContext();
        //内存缓存的总容量的大小为当前进程的1/8，这里的单位是KB，因为下面需要的参数是KB
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = maxMemory / 8;
        //创建内存缓存
        mMemoryCache = new LruCache<String, Bitmap>(cacheSize) {

            //返回计算缓存对象的大小,之所以要/1024就是因为单位要相同要为KB
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                // TODO Auto-generated method stub
                return bitmap.getRowBytes() * bitmap.getHeight() / 1024;
            }
        };

        //创建磁盘缓存
        File disCacheDir = getDiskCacheDir(mContext, "bitmap");
        if (!disCacheDir.exists()) {
            disCacheDir.mkdirs();
        }
        //创建磁盘缓存时要先判断磁盘的剩余容量够不够我们请求
        if (getUsableSpace(disCacheDir) > DISK_CACHE_SIZE) {
            try {
                mDiskLruCache = DiskLruCache.open(disCacheDir, 1, 1, DISK_CACHE_SIZE);
                mIsDiskLruCacheCreated = true;
            } catch (Exception e) {
                // TODO: handle exception
                e.printStackTrace();
            }

        }

    }

    /**
     * 统一使用类方法获取实例
     *
     * @param context
     * @return
     */
    public static ImageLoader build(Context context) {
        return new ImageLoader(context);
    }



    /**
     * 从内存 磁盘缓存中或网络加载图片 然后绑定imageview
     *
     * @param uri
     *            http url
     * @param imageView
     *
     */
    public void bindBitMap(final String uri, final ImageView imageView) {

        bindBitMap(uri, imageView, 0, 0);
    }

    /**
     * 利用线程池的异步加载；还有mmp，网上这个沙雕抄代码还tm的抄错，把这个方法的权限设置成了私有，气死，设置成私有我tm怎么设置长宽，mmp；
     * @param uri
     * @param imageView
     * @param reqWidth
     * @param reqHeight
     */
    public void bindBitMap(final String uri, final ImageView imageView, final int reqWidth, final int reqHeight) {

        imageView.setTag(TAG_KEY_URI, uri);
        //首先当然先从内存缓存中读取，同步进行
        Bitmap bitmap = loadBitmapFromMemCache(uri);
        if (bitmap != null) {
            imageView.setImageBitmap(bitmap);
            return;
        }

        //当内存无时，此时通过线程池异步加载
        Runnable loadBitmapTask = new Runnable() {

            @Override
            public void run() {

                //加载后结果通过Hander要给主UI显示
                Bitmap bitmap = loadBitmap(uri, reqWidth, reqHeight);
                if (bitmap != null) {

                    LoaderResult result = new LoaderResult(imageView, uri, bitmap);
                    mMainHandler.obtainMessage(MESSAGE_POST_RESULT, result).sendToTarget();
                }
            }
        };

        THREAD_POOL_EXECUTOR.execute(loadBitmapTask);
    }

    /**
     *
     * 默认不可在主线程中调用，主要是访问了网络
     * 同步加载图片，顺序是内存，磁盘，网络，返回bitmap
     *
     * @param uri
     *            http url
     * @param reqWidth
     *            需要的宽度
     * @param reqHeight
     *            需要的高度
     * @return
     */
    public Bitmap loadBitmap(String uri, int reqWidth, int reqHeight) {
        Bitmap bitmap = loadBitmapFromMemCache(uri);
        if (bitmap != null) {
            Log.e(TAG, "loadBitmapFromMemCache------>" + uri);
            return bitmap;
        }

        try {
            bitmap = loadBitmapFromDiskCache(uri, reqWidth, reqHeight);
            if (bitmap != null) {
                Log.e(TAG, "loadBitmapFromDiskCache----->" + uri);
                return bitmap;
            }
            //在磁盘也无时就需要开始一个完整的执行了，从网络加载图片后缓存进磁盘，然后再缓存进内存中，就可以返回一张bitmap了
            bitmap = loadBitmapFromHttp(uri, reqWidth, reqHeight);
            Log.e(TAG, "loadBitmapFromHttp----->" + uri);
        } catch (Exception e) {
            // TODO: handle exception
            e.printStackTrace();
        }

        if (bitmap == null && !mIsDiskLruCacheCreated) {
            Log.e(TAG, "DiskCache创建失败");
            //前面操作均出错就采用最终方法，获取网络，然后直接BitmapFactory.decodeStream读流获取bitmap返回；
            bitmap = downloadBitmapFromUrl(uri);

        }
        return null;
    }

    /**
     *
     *
     * @param urlString
     * @return
     */
    private Bitmap downloadBitmapFromUrl(String urlString) {

        Bitmap bitmap = null;
        HttpURLConnection connection = null;
        BufferedInputStream inputStream = null;
        try {
            final URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            inputStream = new BufferedInputStream(connection.getInputStream(), IO_BUFFER_SIZE);
            bitmap = BitmapFactory.decodeStream(inputStream);
        } catch (Exception e) {
            Log.e(TAG, "downloadBitmapFromUrl-->" + e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return null;
    }

    private Bitmap loadBitmapFromMemCache(String url) {

        final String key = hashKeyFormUrl(url);
        Bitmap bitmap = getBitmapFromMemCache(key);
        return bitmap;
    }

    private Bitmap loadBitmapFromDiskCache(String url, int reqWidth, int reqHeight) throws IOException {

        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.e(TAG, "不能从UI线程中读取图片");
        }
        if (mDiskLruCache == null) {
            return null;
        }

        Bitmap bitmap = null;
        String key = hashKeyFormUrl(url);
        DiskLruCache.Snapshot snapshot = mDiskLruCache.get(key);
        if (snapshot != null) {
            FileInputStream fileInputStream = (FileInputStream) snapshot.getInputStream(DISK_CACHE_INDEX);

            FileDescriptor descriptor = fileInputStream.getFD();
            //全部要先经过压缩再返回bitmap给view使用，然后，内存里全部的bitmap是在这里进行压缩后在存进去的，所以内存里的
            //bitmap是压缩过的
            bitmap = mImageResizeer.decodeSampledBitmapFromFileDescriptor(descriptor, reqWidth, reqHeight);
            if (bitmap != null) {
                addBitmapToMemoryCache(key, bitmap);

            }
        }
        return bitmap;
    }

    private Bitmap loadBitmapFromHttp(String url, int reqWidth, int reqHeight) throws IOException {

        //从安卓4.0开始就不允许在 ui 线程中访问网络了
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new RuntimeException("不能从UI线程访问网络");
        }

        if (mDiskLruCache == null) {
            return null;
        }

        String key = hashKeyFormUrl(url);
        DiskLruCache.Editor editor = mDiskLruCache.edit(key);
        if (editor != null) {
            OutputStream outputStream = editor.newOutputStream(DISK_CACHE_INDEX);
            if (downloadUrlToStream(url, outputStream)) {
                editor.commit();
            } else {
                editor.abort();
            }
            mDiskLruCache.flush();
        }

        return loadBitmapFromDiskCache(url, reqWidth, reqHeight);
    }
    private void addBitmapToMemoryCache(String key, Bitmap bitmap) {
        if (getBitmapFromMemCache(key) == null) {
            mMemoryCache.put(key, bitmap);
        }
    }

    private Bitmap getBitmapFromMemCache(String key) {

        return mMemoryCache.get(key);
    }

    private boolean downloadUrlToStream(String url, OutputStream outputStream) {

        HttpURLConnection connection = null;
        BufferedInputStream in = null;
        BufferedOutputStream out = null;
        try {
            final URL url2 = new URL(url);
            connection = (HttpURLConnection) url2.openConnection();
            in = new BufferedInputStream(connection.getInputStream(), IO_BUFFER_SIZE);
            out = new BufferedOutputStream(outputStream, IO_BUFFER_SIZE);

            int b;

            while ((b = in.read()) != -1) {
                out.write(b);
            }
            return true;
        } catch (Exception e) {

            Log.e(TAG, "downloadBitmap-->" + e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }

        }

        return false;
    }

    private String hashKeyFormUrl(String url) {
        String cachekey;

        try {
            final MessageDigest mDigest = MessageDigest.getInstance("MD5");
            mDigest.update(url.getBytes());
            cachekey = bytesToHexString(mDigest.digest());

        } catch (Exception e) {
            cachekey = String.valueOf(url.hashCode());
        }
        return cachekey;

    }

    private String bytesToHexString(byte[] bytes) {

        StringBuilder sBuilder = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            String hex = Integer.toHexString(0xFF & bytes[i]);
            if (hex.length() == 1) {
                sBuilder.append('0');
            }
            sBuilder.append(hex);
        }
        return sBuilder.toString();
    }

    private File getDiskCacheDir(Context context, String uniqueName) {

        boolean externalStorageAvailable = Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED);
        final String cachePath;
        if (externalStorageAvailable) {
            cachePath = context.getExternalCacheDir().getPath();

        } else {
            cachePath = context.getCacheDir().getPath();
        }

        return new File(cachePath + File.separator + uniqueName);
    }

    private long getUsableSpace(File path) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
            return path.getUsableSpace();
        }
        final StatFs statFs = new StatFs(path.getPath());
        return (long) statFs.getBlockSize() * (long) statFs.getAvailableBlocks();

    }

    //为线程池准备
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();//获取CPU核心数

    private static final int CORE_POOL_SIZE = CPU_COUNT + 1;

    private static final int MAXIMUM_POOL_SIZE = CPU_COUNT * 2 + 1;

    private static final long KEEP_ALIVE = 10L;



    private static final long DISK_CACHE_SIZE = 50 * 1024 * 1024;
    private static final int IO_BUFFER_SIZE = 8 * 1024;
    private static final int DISK_CACHE_INDEX = 0;
    private boolean mIsDiskLruCacheCreated = false;

    private static final ThreadFactory sThreadFactory = new ThreadFactory() {
        //AtomicInteger是线程安全的integer
        private final AtomicInteger mCount = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            // TODO Auto-generated method stub
            return new Thread(r, "ImageLoader:" + mCount.getAndIncrement());
            // mCount.getAndIncrement()的意思是 ++m
        }
    };

    //线程池实现
    public static final Executor THREAD_POOL_EXECUTOR = new ThreadPoolExecutor(CORE_POOL_SIZE, MAXIMUM_POOL_SIZE,
            KEEP_ALIVE, TimeUnit.SECONDS, new LinkedBlockingDeque<Runnable>(), sThreadFactory);

    //ui 线程的handler实现
    private Handler mMainHandler = new Handler(Looper.getMainLooper()) {
        public void handleMessage(android.os.Message msg) {

            LoaderResult result = (LoaderResult) msg.obj;
            ImageView imageView = result.imageView;

            String uri = (String) imageView.getTag(TAG_KEY_URI);
            // 判断url是否发生改变，之所以要判断是由于如果是在列表框架中调用，由于列表框架通过使用的view复用概念，可能会导致图片错位，此时要加一层判断
            if (uri.equals(result.uri)) {
                imageView.setImageBitmap(result.bitmap);

            } else {
                Log.e(TAG, "url已经改变");
            }
        };
    };

    //将加载结果封装成一个对象便于 handler机制 传输,并私有化，这个LoaderResult只需供ImageLoader使用，所以静态内部类即可满足需求，减少了暴露类的数量
    private static class LoaderResult {
        public ImageView imageView;
        public String uri;
        public Bitmap bitmap;

        public LoaderResult(ImageView imageView, String uri, Bitmap bitmap) {
            this.imageView = imageView;
            this.uri = uri;
            this.bitmap = bitmap;
        }

    }
}