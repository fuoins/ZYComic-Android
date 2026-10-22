package eu.kanade.tachiyomi.source.model
import okhttp3.Response
sealed class Filter<T>(val name:String,var state:T) { abstract class Text(name:String,state:String=""):Filter<String>(name,state); abstract class Select<V>(name:String,val values:Array<V>,state:Int=0):Filter<Int>(name,state) }
data class FilterList(val list:List<Filter<*>>):List<Filter<*>> by list { constructor(vararg fs:Filter<*>):this(fs.asList()) }
class MangasPage(val mangas:List<SManga>,val hasNextPage:Boolean)
open class Page(val index:Int,var url:String="",var imageUrl:String?=null)
interface SChapter { var url:String; var name:String; var chapter_number:Float; var date_upload:Long; companion object { fun create():SChapter=SChapterImpl() } }
class SChapterImpl:SChapter { override var url=""; override var name=""; override var chapter_number=0f; override var date_upload=0L }
interface SManga { var url:String; var title:String; var thumbnail_url:String?; var author:String?; var description:String?; var genre:String?; var status:Int; var initialized:Boolean; companion object { const val COMPLETED=2; const val ONGOING=1; fun create():SManga=SMangaImpl() } }
class SMangaImpl:SManga { override var url=""; override var title=""; override var thumbnail_url:String?=null; override var author:String?=null; override var description:String?=null; override var genre:String?=null; override var status=0; override var initialized=false }
class SMangaUpdate(val manga:SManga,val chapters:List<SChapter>)
