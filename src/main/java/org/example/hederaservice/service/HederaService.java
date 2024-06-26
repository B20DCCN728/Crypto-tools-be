package org.example.hederaservice.service;

import ch.qos.logback.core.status.StatusBase;
import com.hedera.hashgraph.sdk.*;
import org.example.hederaservice.configuration.ClientHelper;
import org.example.hederaservice.dto.result.CheckBalanceResultDto;
import org.example.hederaservice.dto.result.CreateResultResponseDto;
import org.example.hederaservice.dto.task.AssociateResponseDto;
import org.example.hederaservice.dto.task.CheckBalanceDto;
import org.example.hederaservice.dto.task.CreateAccountDto;
import org.example.hederaservice.dto.result.ResultResponseDto;
import org.example.hederaservice.dto.task.TranferResponseDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@Service
public class HederaService {
    // Define Simp Message SendingOperations to send messages to the client
    @Autowired
    private SimpMessageSendingOperations simpMessageSendingOperations;

    // Multiple Transfer Method to transfer HBAR from one account to multiple accounts
    public List<ResultResponseDto> multipleTransfer(TranferResponseDto tranferResponseDto) throws InterruptedException {
        AccountId accountId = AccountId.fromString(tranferResponseDto.getAccountAddress());
        PrivateKey privateKey = PrivateKey.fromString(tranferResponseDto.getPrivateKey());
        Client client = getClient(
                accountId,
                privateKey,
                "mainnet"
        );
        return tranferResponseDto.getReceivedAddresses().stream()
                .map(receiveAccountId -> {
                    try {
                        TransactionResponse response = new TransferTransaction()
                                .addHbarTransfer(accountId, Hbar.fromTinybars(tranferResponseDto.getAmount() * -100000000))
                                .addHbarTransfer(AccountId.fromString(receiveAccountId), Hbar.fromTinybars(tranferResponseDto.getAmount() * 100000000))
                                .execute(client);
                        ResultResponseDto resultResponseDto = ResultResponseDto.builder()
                                .status(response.getReceipt(client).status)
                                .receivedAddress(receiveAccountId)
                                .build();
                        simpMessageSendingOperations.convertAndSend("/topic/transfer", resultResponseDto);
                        return resultResponseDto;
                    } catch (TimeoutException | PrecheckStatusException | ReceiptStatusException e) {
                        throw new RuntimeException(e);
                    }
                }).toList();
    }

    // Multiple Associate Method to associate multiple tokens to multiple accounts
    public List<ResultResponseDto> multipleAssociate(AssociateResponseDto associateResponseDto) throws InterruptedException {
        AccountId accountId = AccountId.fromString(associateResponseDto.getAccountAddress());
        PrivateKey privateKey = PrivateKey.fromString(associateResponseDto.getPrivateKey());
        Client client = getClient(
                accountId,
                privateKey,
                "mainnet"
        );

        return associateResponseDto.getAssociatedAddresses().stream()
                .map(receiveAccountId -> {
                    try {
                        List<TokenId> tokenIds = associateResponseDto.getTokens().stream()
                                .map(TokenId::fromString)
                                .toList();
                        for (TokenId tokenId : tokenIds) {
                            TokenAssociateTransaction transaction = new TokenAssociateTransaction()
                                    .setAccountId(AccountId.fromString(receiveAccountId))
                                    .setTokenIds(List.of(tokenId));
                            TransactionResponse txResponse = transaction.freezeWith(client).sign(privateKey).execute(client);
                            TransactionReceipt receipt = txResponse.getReceipt(client);
                            Status transactionStatus = receipt.status;
                            ResultResponseDto resultResponseDto = ResultResponseDto.builder()
                                    .status(transactionStatus)
                                    .receivedAddress(receiveAccountId)
                                    .build();
                            simpMessageSendingOperations.convertAndSend("/topic/associate", resultResponseDto);
                            return resultResponseDto;
                        }
                    } catch (TimeoutException | PrecheckStatusException | ReceiptStatusException e) {
                        throw new RuntimeException(e);
                    }
                    return null;
                }).toList(); // Return the list of ResultResponseDto
    }

    // Multiple Create Account Method to create multiple accounts
    public List<CreateResultResponseDto> multipleCreateAccount(CreateAccountDto createAccountDto)
            throws InterruptedException,
            PrecheckStatusException,
            TimeoutException, ReceiptStatusException {
        AccountId accountId = AccountId.fromString(createAccountDto.getAccountAddress());
        PrivateKey privateKey = PrivateKey.fromString(createAccountDto.getPrivateKey());
        Client client = getClient(
                accountId,
                privateKey,
                "mainnet"
        );

        List<CreateResultResponseDto> createResultResponseDtos = new ArrayList<>();
        for(int i = 0;i < createAccountDto.getNumberOfAccounts();i++) {
            CreateResultResponseDto createResultResponseDto = new CreateResultResponseDto();

            PrivateKey newAccountPrivateLKey = PrivateKey.generateED25519();
            System.out.println("Private key of the new account: " + newAccountPrivateLKey.toString());
            PublicKey newAccountPublicKey = newAccountPrivateLKey.getPublicKey();
            System.out.println("Public key of the new account: " + newAccountPublicKey.toString());

            TransactionResponse newAccount = new AccountCreateTransaction()
                    .setKey(newAccountPublicKey)
                    .setInitialBalance(Hbar.fromTinybars(0))
                    .execute(client);

            AccountId newAccountId = newAccount.getReceipt(client).accountId;
            createResultResponseDto.setStatus(newAccount.getReceipt(client).status);

            simpMessageSendingOperations.convertAndSend("/topic/create", createResultResponseDto);
            assert newAccountId != null;
            createResultResponseDto.setAccountAddress(newAccountId.toString());
            createResultResponseDto.setPrivateKey(newAccountPrivateLKey.toString());
            createResultResponseDtos.add(createResultResponseDto);
        }
        return createResultResponseDtos;
    }

    // Check Balance Method to check the balance of an account
    public List<CheckBalanceResultDto> checkBalance(CheckBalanceDto checkBalanceDto) throws InterruptedException, PrecheckStatusException, TimeoutException {
        AccountId accountId = AccountId.fromString(checkBalanceDto.getAccountAddress());
        PrivateKey privateKey = PrivateKey.fromString(checkBalanceDto.getPrivateKey());
        Client client = getClient(
                accountId,
                privateKey,
                "mainnet"
        );

        return checkBalanceDto.getAccountAddresses().stream()
                .map(accountAddress -> {
                    AccountBalance accountBalance = null;
                    try {
                        accountBalance = new AccountBalanceQuery()
                                .setAccountId(AccountId.fromString(accountAddress))
                                .execute(client);

                        // Check if the token id is null
                        if (checkBalanceDto.getTokenId() == null) {
                            return CheckBalanceResultDto.builder()
                                    .accountAddress(accountAddress)
                                    .balance((int) accountBalance.hbars.toTinybars())
                                    .build();
                        } else {
                            Map<TokenId, Long> tokenBalances = accountBalance.tokens;

                            for(Map.Entry<TokenId, Long> entry : tokenBalances.entrySet()) {
                                if(entry.getKey().toString().equals(checkBalanceDto.getTokenId())) {
                                    return CheckBalanceResultDto.builder()
                                            .accountAddress(accountAddress)
                                            .tokenId(checkBalanceDto.getTokenId())
                                            .balance((int) entry.getValue().longValue())
                                            .build();
                                }
                            }

                            return CheckBalanceResultDto.builder()
                                    .accountAddress(accountAddress)
                                    .tokenId(checkBalanceDto.getTokenId())
                                    .balance(0)
                                    .build();
                        }
                    } catch (TimeoutException | PrecheckStatusException e) {
                        throw new RuntimeException(e);
                    }
                }).toList();
    }

    // Define the getClient method
    public Client getClient(
            AccountId operatorId,
            PrivateKey operatorKey,
            String network
    ) throws InterruptedException
    {
        return ClientHelper.forName(network)
                .setOperator(
                        operatorId,
                        operatorKey
                );
    }
}
