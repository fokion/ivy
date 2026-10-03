@browser
Feature: Hacker News
  The stories of the Hacker News front page open their own page.
  Needs the internet; screenshots and hacker-news.json go to examples/browser/output.

  Scenario: open the 3rd story
    Given I open "https://news.ycombinator.com"
    And I take a screenshot "hacker-news-front-page.png"
    When I open story 3
    Then I am no longer on "https://news.ycombinator.com/"
    And I take a screenshot "hacker-news-third-item.png"
    And I save the story to "hacker-news.json"
