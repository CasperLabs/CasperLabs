Feature: Clarity

  # Implemented test_clarity.py : test_clarity_running
  Scenario: Clarity is running
     Given: Single Node Network With Clarity
       And: A running seleniumHQ container
      When: Visiting http://CLARITY-HOST-NAME:8080
      Then: The content of page contains "<title>CasperLabs Clarity - Home</title>"

  # Implemented test_clarity.py : test_create_account_key
  Scenario: User can create/delete account key and request tokens by using stored version faucet
     Given: Single Node Network With Clarity
       And: A running seleniumHQ container
      When: Visiting http://CLARITY-HOST-NAME:8080/#/accounts
       And: Log into the Clarity
       And: Click `Create Account Key`, and input a account name
      Then: A new account with no no token has been created
      when: Click `Create Account Key` again, and input another account name
      Then: Another new account with no no token has been created
      When: Using the first created account to request tokens
       And: The faucet request succeed
      Then: In Accounts Key page, we can see the balance of the account is 10,000,000
       And: There should be 2 deploys from the faucet account, one for init stored version faucet, the other is for calling faucet method
      When: Using the second created account to request tokens
       And: The faucet request succeed
      Then: In Accounts Key page, we can see the balance of the account is 10,000,000
       And: There should be 3 deploys from the faucet account, two for previous faucet, and the last one is for calling faucet method
      When: When click the delete button of the account
      Then: The account is deleted
