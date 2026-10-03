@http
Feature: HTTP steps

  Scenario: get JSON
    When I GET "/json"
    Then the status is 200
    And the JSON field "token" is "abc"
