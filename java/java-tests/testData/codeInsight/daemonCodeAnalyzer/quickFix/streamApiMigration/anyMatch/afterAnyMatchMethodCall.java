// "Replace with anyMatch()" "true-preview"

import java.util.List;

public class Main {
  public void testAssignment(List<String> data) {
      if (data.stream().map(String::trim).anyMatch(trimmed -> !trimmed.isEmpty())) {
          System.out.println("Found!!!");
      }
  }
}