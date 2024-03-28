package paseto

type GenericAuthHeaders struct {
	Authorization string
}

type webappResponse struct {
	WalletAddress string `json:"walletAddress"`
}
