package android.security.keystore
class KeyGenParameterSpec private constructor(): java.security.spec.AlgorithmParameterSpec { class Builder(alias:String,purposes:Int) { fun setBlockModes(vararg s:String)=this; fun setEncryptionPaddings(vararg s:String)=this; fun setKeySize(n:Int)=this; fun build()=KeyGenParameterSpec() } }
object KeyProperties { const val KEY_ALGORITHM_AES="AES"; const val PURPOSE_ENCRYPT=1; const val PURPOSE_DECRYPT=2; const val BLOCK_MODE_GCM="GCM"; const val ENCRYPTION_PADDING_NONE="NoPadding" }
