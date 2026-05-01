package scala.reflect

import scala.annotation.targetName

package object runtime:
  val universe: scala.reflect.runtime.RuntimeUniverse.type = scala.reflect.runtime.RuntimeUniverse

  @targetName("universe")
  def runtimeUniverse: scala.reflect.api.JavaUniverse = scala.reflect.runtime.RuntimeUniverse

  def currentMirror: universe.Mirror = universe.currentMirror
