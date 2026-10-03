@exec
Feature: Shell steps
  Steps written in Gherkin, run by ivy.

  Background:
    Given a working directory

  Scenario: echo
    When I run "echo hello"
    Then the exit code is 0
    And the output is "hello"

  Scenario Outline: arithmetic <a> + <b>
    When I run "echo $(( <a> + <b> ))"
    Then the output is "<sum>"

    Examples:
      | a | b | sum |
      | 1 | 2 | 3   |
      | 5 | 5 | 10  |

  Scenario: step variables from the captured arguments
    When I run the number after 3
    Then the output is "4"

  Scenario: doc strings and tables
    When I run the script:
      """
      echo multi
      echo line
      """
    Then the output contains "line"
    When the output lists:
      | name |
      | ivy  |
    Then the output contains "ivy"

  @wip
  Scenario: excluded by the tag filter
    When I run "exit 1"
