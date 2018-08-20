[TOC]



# bitmap的高效加载

## 简介

- Bitmap 在 Android 中指的是一张图片，那么如何加载一张图片呢？BitmapFactory 类提供了4类方法：decodeFile，decodeResource，decodeStream, decodeByteArray; 分别用于支持从文件系统，资源，输入流以及字节数组中加载出一个 Bitmap 对象；其中 decodeFile，decodeResource 又间接调用了 decodeStream 方法，这4类方法最终在 android 的底层实现；
- 一个重要的问题，我们要高效加载 bitmap 干什么？其实，由于Android 对单个应用所施加的内存限制，在加载很多 Bitmap 的时候很容易出现内存溢出。这就是问题所在

## 如何高效加载

- 其实简单地讲：核心思想就是采用 BitmapFactory.Options 来加载所需尺寸的图片。BitmapFactory.Options 又是什么呢？
- 通过 BitmapFactory.Options 就可以按一定的采样率来加载缩小后的图片，将缩小后的图片在 ImageView 中显示，这样就会降低内存占用从而在一定程度上避免 OOM 。那么 ，缩小后的图片符合我们的程序要求吗？
- 举个例子，假设现在要给 ImageView 来显示图片，很多时候 ImageView 并没有图片的原始尺寸那么大，这个时候把整个图片加载进来后再设给ImageView 就没有必要了，因为 ImageView 并没有办法显示原始的图片；所以我们加载缩小的图片就可以；那么怎么加载缩小的图片呢？
- 前面说了，通过 BitmapFactory.Options 来缩放图片

## 使用BitmapFactory.Options

- BitmapFactory.Options 是如何缩放图片的呢？
- BitmapFactory.Options 主要是用到了它的 inSampleSize 参数，即采样率。采样率同时作用于宽高；官方文档指出采样率应该总是为 2 的指数
  - 当 inSampleSize 为 1 时，采样后的图片大小为原始图片的原始大小
  - 当 inSampleSize 为 2 时，那么采样后的图片其宽高均为原图大小的 1/2；所以像素数为原图的 1/4；其占用的内存大小也为原图的 1/4 ；

## 使用方法

- 举个例子，比如 ImageView 的大小是 100`*`100 像素，而图片的原始大小为 200`*`200 ，那么采样率 设置为 2 即可，如果图片的大小为 200`*`300 ,那么采样率该设置为多少呢，其实应该设置为2，这是因为不能缩小到比存放这张 bitmap 的容器的大小 还要小；

- 那么我们如何准确地知道采样率该设置为多少呢？难道到原始图片那里一张一张查询吗？当然不是，不然还要 BitmapFactory.Options 干什么，其实有一个固定的模板来取到这个采样率

  1. 将 BitmapFactory.Options 的 inJustDecodeBounds 参数设为 true 并加载图片（注意，这里所说的加载是 BitmapFactory只会去解析图片的原始宽/高信息，并不会真正地加载图片，属轻量级的操作）；
  2. 从 BitmapFactory.Options 中取出图片的原始宽高信息，它们对应于 outWidth 和 outHeight 参数；
  3. 根据采样率的规则结合我们传进去的我们所需要的大小计算出采样率；注意：之前所做的一切就是为了这里可以计算出采样率；
  4. 将 BitmapFactory.Options 的 inJustDecodeBounds 参数设为 false，然后用得到的采样率来重新加载图片；

  ​

## 代码实现

- 将上面4个流程用程序来实现

- ```java
  //这里的 Resource 由 getResource() 传入
  public static Bitmap decodeSampledBitmapFromResource(Resources res, int resId, int reqWidth, int reqHeight) {

          BitmapFactory.Options options = new BitmapFactory.Options();
          options.inJustDecodeBounds = true;
          BitmapFactory.decodeResource(res, resId, options);

          options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
          Log.e("MainActivity", "decodeSampledBitmapFromResource: " + options.inSampleSize);

          options.inJustDecodeBounds = false;
          return BitmapFactory.decodeResource(res, resId, options);
      }

      private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
          int height = options.outHeight;
          int width = options.outWidth;
          int inSampleSize = 1;

          if (height > reqHeight || width > reqWidth) {
              int halfHeight = height / 2;
              int halfWidth = width / 2;

              while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= 					reqWidth) {
                  inSampleSize *= 2;
              }
          }
          return inSampleSize;
  }
  ```

- 简单调用即可：

  ```java
  Bitmap bitmap = decodeSampledBitmapFromResource(getResources(),R.drawable.aaa,100,100);
  ```

  ​