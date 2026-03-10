object Settings {
  def x = 0
}

case class Settings(value: Double = x) // error
