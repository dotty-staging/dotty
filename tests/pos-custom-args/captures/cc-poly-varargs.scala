abstract class Source[+T, cap Cap]

extension[T, cap Cap](src: Source[T, {Cap}]^)
  def transformValuesWith[U](f: (T -> U)^{Cap}): Source[U, {Cap}]^{src, f} = ???

def race[T, cap Cap](sources: Source[T, {Cap}]^{Cap}*): Source[T, {Cap}]^{Cap} = ???

def either[T1, T2, cap Cap](
    src1: Source[T1, {Cap}]^{Cap},
    src2: Source[T2, {Cap}]^{Cap}): Source[Either[T1, T2], {Cap}]^{Cap} =
  val left = src1.transformValuesWith(Left(_))
  val right = src2.transformValuesWith(Right(_))
  race(left, right)
