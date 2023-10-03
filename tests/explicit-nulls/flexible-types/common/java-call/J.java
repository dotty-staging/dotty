public class J {

  public class K {}

  public J self() {
    return this;
  }

  public String f1() {
    return "";
  }

  public int f2() {
    return 0;
  }

  public K f3() {
    return null;
  }

  public <T> T g1() {
    return null;
  }
}

class J2<T> {
  public T x = null;
}