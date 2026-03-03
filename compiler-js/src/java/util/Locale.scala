package java.util

class Locale(language: String, country: String, variant: String) {
  def this(language: String, country: String) = this(language, country, "")
  def this(language: String) = this(language, "", "")

  def getLanguage: String = language
  def getCountry: String = country
  def getVariant: String = variant
  override def toString: String =
    if (country.isEmpty) language
    else if (variant.isEmpty) s"${language}_${country}"
    else s"${language}_${country}_${variant}"
}

object Locale {
  val ENGLISH: Locale = new Locale("en")
  val US: Locale = new Locale("en", "US")
  val ROOT: Locale = new Locale("", "", "")
  def getDefault: Locale = ENGLISH
}
