Feature: Failing assertion

  Scenario: wrong output
    When I run "echo a"
    Then the output is "b"
