//> using options -language:experimental.modularity -source future

import compiletime.*
import compiletime.ops.int.*

extension [N <: Int : Precise](n: N)
  def +![M <: Int : Precise](m: M) =
    (n + m).asInstanceOf[N + M]

case class Vec(tracked val size: Int):
  def ++(v2: Vec) = Vec(size +! v2.size)
  def zip(v2: Vec { val size: Vec.this.size.type }) = Vec(size)

@main def Test =
  val v1 = Vec(1)
  val v2 = Vec(2)
  val v3 = v1 ++ v2
  val v4 = v2 ++ v1
  val v5 = v3.zip(v4)
  val v6 = v3.zip(v1) // error
