package middleware

import (
	"os"

	log "github.com/sirupsen/logrus"
)

func CheckAuthorization(decryptedWalletAddress any) bool {
	AllowedWalletAddress := os.Getenv("MASTERNODE_WALLET")
	if AllowedWalletAddress == "*" {
		return true
	}
	if decryptedWalletAddress != AllowedWalletAddress {
		log.WithFields(log.Fields{
			"err": "Updates Not Allowed for the Following Wallet Address",
		}).Error("Updates Not Allowed for the Following Wallet Address")
		return false
	}
	return true

}
