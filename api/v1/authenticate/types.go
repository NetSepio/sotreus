package authenticate

type AuthenticateRequest struct {
	ChallengeId string `json:"challengeId"`
	Signature   string `json:"signature"`
	PubKey      string `json:"pubKey"`
}

type AuthenticatePayload struct {
	Status  int64  `json:"status"`
	Success bool   `json:"success"`
	Message string `json:"message"`
	Token   string `json:"token"`
}
