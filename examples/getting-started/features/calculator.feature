@smoke
Feature: Arithmetic in the shell
  Gherkin scenarios run directly; the steps are defined in steps/shell.steps.yml.

  Scenario: add two numbers
    When I run "echo $(( 2 + 3 ))"
    Then the exit code is 0
    And the output is "5"

  Scenario Outline: multiply <a> by <b>
    When I run "echo $(( <a> * <b> ))"
    Then the output is "<product>"

    Examples:
      | a | b | product |
      | 2 | 3 | 6       |
      | 7 | 6 | 42      |

  @slow
  Scenario: skipped with --tags "not @slow"
    When I run "sleep 1"
