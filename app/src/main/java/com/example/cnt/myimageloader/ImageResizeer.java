package com.example.cnt.myimageloader;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.FileDescriptor;

/**
 * 图片压缩，就是使用BitmapFactory.Options参数，通过改变 BitmapFactory.Options参数里的inSampleSize来达到压缩目的
 * Created by cnt on 2018/8/18.
 */

public class ImageResizeer {
    private static final String TAG = "ImageResizeer";

    public ImageResizeer() {
    }

    //从 Resource 中读取
    public Bitmap decodeSampledBitMapFromResource(Resources res, int resId, int reqWidth, int reqHeight) {

        BitmapFactory.Options options = new BitmapFactory.Options();

        options.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(res, resId, options);

        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);

        options.inJustDecodeBounds = false;

        return BitmapFactory.decodeResource(res, resId, options);

    }
    //从文件中读取，不过这里内部我们使用的是BitmapFactory.decodeFileDescriptor是因为如果调用的是decodeStream方法，由于 FileInputStream 是一种有序的文件流，而我们后面是要去
    //读这个流2次的，第一次读的是对的，但是第二次decodeStream读则返回的是 null,此时就选择调用decodeFileDescriptor读文件描述符
    public Bitmap decodeSampledBitmapFromFileDescriptor(FileDescriptor fd, int reqWidth, int reqHeight) {
        BitmapFactory.Options options = new BitmapFactory.Options();

        options.inJustDecodeBounds = true;

        BitmapFactory.decodeFileDescriptor(fd, null, options);

        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);

        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeFileDescriptor(fd, null, options);

    }

    //计算SampleSize
    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        // TODO Auto-generated method stub
        if (reqWidth == 0 || reqHeight == 0) {
            return 1;
        }

        int height = options.outHeight;
        int wight = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || wight > reqWidth) {

            int halfHeight = height / 2;
            int halfWidth = wight / 2;

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }

        return inSampleSize;
    }

}
