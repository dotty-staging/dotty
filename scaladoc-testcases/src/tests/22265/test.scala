object breaks {
  TestBuilder:
    import List.empty
    println("123") // LTS has a lgitimate error otherwise
}
