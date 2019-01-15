Feature: Hashicorp Vault support
    Storing and retrieving Tessera public/private key pairs from a Hashicorp Vault

#    Scenario: A key pair stored in the vault is retrieved by Tessera on start up
#        Given the dev vault server has been started
#        And the vault is initialised and unsealed
#        And the vault contains a key pair
#        And the configfile contains the correct vault configuration
#        And the configfile contains the correct key data
#        When Tessera is started
#        Then Tessera will retrieve the key pair from the vault
#
#    Scenario: A key pair generated by Tessera is stored in the vault
#        Given the dev vault server has been started
#        And the vault is initialised and unsealed
#        When Tessera keygen is used with the Hashicorp options provided
#        Then a new key pair "tessera/nodeA" will be added to the vault
#        And a new key pair "tessera/nodeB" will be added to the vault

    Scenario: Using TLS, a key pair stored in the vault is retrieved by Tessera on start up
        Given the vault server has been started with TLS-enabled
        And the vault is initialised and unsealed
#        And the vault contains a key pair
#        And the configfile contains the correct TLS-enabled vault configuration
#        And the configfile contains the correct key data
#        When Tessera is started
#        Then Tessera will retrieve the key pair from the vault
