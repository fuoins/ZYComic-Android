package android.graphics
class Bitmap(val width: Int = 1, val height: Int = 1) {
    fun recycle() = Unit
}
object BitmapFactory {
    class Options {
        var inJustDecodeBounds: Boolean = false
        var outWidth: Int = 1
        var outHeight: Int = 1
        var inSampleSize: Int = 1
    }
    fun decodeByteArray(data: ByteArray, offset: Int, length: Int, options: Options): Bitmap? = if (options.inJustDecodeBounds) null else Bitmap()
}
