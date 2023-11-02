package cryptosign

import (
	"errors"
	"log"
	"time"

	"github.com/NetSepio/sotreus/api/v1/authenticate/challengeid"
	"github.com/ethereum/go-ethereum/common/hexutil"
	"golang.org/x/crypto/nacl/sign"
	"golang.org/x/crypto/sha3"
)

var (
	ErrFlowIdNotFound = errors.New("flow id not found")
)

func CheckSign(signature string, flowId string, message string, pubKey string) (string, bool, error) {
	// get flowid from the local db file
	signatureInBytes, err := hexutil.Decode(signature)
	if err != nil {
		return "", false, err
	}
	sha3_i := sha3.New256()
	signatureInBytes = append(signatureInBytes, []byte(message)...)
	pubBytes, err := hexutil.Decode(pubKey)
	if err != nil {
		return "", false, err
	}
	sha3_i.Write(pubBytes)
	sha3_i.Write([]byte{0})
	hash := sha3_i.Sum(nil)
	addr := hexutil.Encode(hash)
	log.Printf("pub key - %v\n", hexutil.Encode(pubBytes))

	localData, exists := challengeid.Data[flowId]
	if !exists {
		return "", false, ErrFlowIdNotFound
	}
	if time.Since(localData.Timestamp) > 1*time.Hour {
		return "", false, errors.New("challenge id expired for the request")
	}
	if addr != localData.WalletAddress {
		return "", false, err
	}
	msgPro := signatureInBytes[sign.Overhead:]
	log.Printf("msg pro - %v\n", string(msgPro))

	msgGot, matches := sign.Open(nil, signatureInBytes, (*[32]byte)(pubBytes))
	log.Printf("msg got - %v\n", string(msgGot))
	log.Printf("msg needed - %v\n", message)
	if !matches || string(msgGot) != message {
		log.Println("no match or no equal")
		return "", false, err
	} //equate the wallet address from the flow id and the reeived wallet address
	return localData.WalletAddress, true, nil
}
