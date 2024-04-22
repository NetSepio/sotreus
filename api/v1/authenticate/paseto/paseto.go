package paseto

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"

	"github.com/sirupsen/logrus"
	log "github.com/sirupsen/logrus"

	"github.com/gin-gonic/gin"
)

var (
	ErrAuthHeaderMissing = errors.New("authorization header is required")
)

func PASETO(c *gin.Context) {
	var headers GenericAuthHeaders
	err := c.BindHeader(&headers)
	if err != nil {
		err = fmt.Errorf("failed to bind header, %s", err)
		log.WithFields(log.Fields{
			"err": err,
		}).Error("failed to bind")

		c.AbortWithStatus(http.StatusInternalServerError)
		return
	}
	if headers.Authorization == "" {
		log.WithFields(log.Fields{
			"err": err,
		}).Error("Autherisation header is missing")
		c.Abort()
		return
	}
	token := headers.Authorization
	splitToken := strings.Split(token, "Bearer ")
	authToken := splitToken[1]

	//auth req to gateway
	contractReq, err := http.NewRequest(http.MethodGet, "https://dev.gateway.sotreus.com/api/v1.0/webapp/auth", nil)
	if err != nil {
		logrus.Errorf("failed to send request: %s", err)
		c.AbortWithStatus(http.StatusUnauthorized)
		return
	}
	contractReq.Header.Set("Authorization", "Bearer "+authToken)
	client := &http.Client{}
	resp, err := client.Do(contractReq)
	if err != nil {
		logrus.Errorf("failed to send request: %s", err)
		c.AbortWithStatus(http.StatusUnauthorized)
		return
	}
	if resp.StatusCode != 200 {
		logrus.Errorf("Error in response: %s", err)
		c.AbortWithStatus(http.StatusUnauthorized)
		return
	}
	defer resp.Body.Close()
	var responseBody webappResponse
	err = json.NewDecoder(resp.Body).Decode(&responseBody)
	fmt.Println("Wallet Address: ", responseBody.Payload.WalletAddress)
	if err != nil {
		fmt.Printf("Failed to decode response body: %s\n", err)
		return
	} else {
		c.Set("walletAddress", responseBody.Payload.WalletAddress)
		c.Next()
	}
	// parser := gopaseto.NewParser()
	// parser.AddRule(gopaseto.NotExpired())
	// publickey := auth.Getpublickey()
	// parsedToken, err := parser.ParseV4Public(publickey, pasetoToken, nil)

	// if err != nil {
	// 	err = fmt.Errorf("failed to scan claims for paseto token, %s", err)
	// 	log.WithFields(log.Fields{
	// 		"err": err,
	// 	}).Error("failed to bindfailed to scan claims for paseto token")
	// 	c.AbortWithStatus(http.StatusUnauthorized)
	// 	return
	// } else {
	// 	jsonvalue := parsedToken.ClaimsJSON()
	// 	ClaimsValue := claims.CustomClaims{}
	// 	json.Unmarshal(jsonvalue, &ClaimsValue)
	// 	c.Set("walletAddress", ClaimsValue.WalletAddress)
	// 	c.Next()
	// }

}
