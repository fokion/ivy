@api
Feature: Users and posts
  The scenarios read like prose; every step is one of the same blocks from ../lib.

  Scenario: a known user
    When I look up user 1
    Then the username is "Bret"

  Scenario: a user and their posts
    When I look up the posts of user 2
    Then they have 10 posts

  Scenario Outline: usernames of several users
    When I look up user <id>
    Then the username is "<username>"

    Examples:
      | id | username  |
      | 1  | Bret      |
      | 2  | Antonette |
      | 3  | Samantha  |

  Scenario: an unknown user
    When I look up user 9999 expecting 404
    Then the response body is empty
