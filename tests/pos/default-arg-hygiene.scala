object Settings {
  def x = 0
}

import Settings.*

case class Settings(value: Double = x) // OK, imported
