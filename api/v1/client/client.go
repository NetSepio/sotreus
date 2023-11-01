package client

import (
	"net/http"

	"github.com/NetSepio/sotreus/api/v1/authenticate/paseto"
	"github.com/NetSepio/sotreus/api/v1/middleware"
	"github.com/NetSepio/sotreus/core"
	"github.com/NetSepio/sotreus/model"
	"github.com/gin-gonic/gin"
	log "github.com/sirupsen/logrus"
	"github.com/skip2/go-qrcode"
)

// ApplyRoutes applies router to gin Router
func ApplyRoutes(r *gin.RouterGroup) {
	g := r.Group("/client")
	{
		g.Use(paseto.PASETO)
		g.POST("", createClient)
		g.GET("/:id", readClient)
		g.PATCH("/:id", updateClient)
		g.DELETE("/:id", deleteClient)
		g.GET("", readClients)
		g.GET("/:id/config", configClient)
		g.GET("/:id/email", emailClient)
		g.POST("/register", registerClient)
	}
}

// swagger:route POST /client Client createClient
//
// Create client
//
// Create client based on the given client model.
// responses:
//  201: clientSucessResponse
//  400: badRequestResponse
//	401: unauthorizedResponse
//  500: serverErrorResponse

func createClient(c *gin.Context) {
	var data model.Client

	requestedWalletAddress, _ := c.Get("walletAddress")
	accept := middleware.CheckAuthorization(requestedWalletAddress)
	if !accept {
		log.WithFields(log.Fields{
			"err": "Updates Not Allowed for the Following Wallet Address",
		}).Error("Updates Not Allowed for the Following Wallet Address")
		response := core.MakeErrorResponse(401, "Updates Not Allowed for the Following Wallet Address", nil, nil, nil)
		c.JSON(http.StatusUnauthorized, response)
		return
	}

	if err := c.ShouldBindJSON(&data); err != nil {
		log.WithFields(log.Fields{
			"err": err,
		}).Error("failed to bind")

		response := core.MakeErrorResponse(400, err.Error(), nil, nil, nil)
		c.JSON(http.StatusUnprocessableEntity, response)
		return
	}

	client, err := core.CreateClient(&data)
	if err != nil {
		log.WithFields(log.Fields{
			"err": err,
		}).Error("failed to create client")

		response := core.MakeErrorResponse(500, err.Error(), nil, nil, nil)
		c.JSON(http.StatusInternalServerError, response)
		return
	}

	response := core.MakeSucessResponse(201, "client created", nil, client, nil)

	c.JSON(http.StatusOK, response)
}

func registerClient(c *gin.Context) {
	var data model.Client

	requestedWalletAddress, _ := c.Get("walletAddress")
	accept := middleware.CheckAuthorization(requestedWalletAddress)
	if !accept {
		log.WithFields(log.Fields{
			"err": "Updates Not Allowed for the Following Wallet Address",
		}).Error("Updates Not Allowed for the Following Wallet Address")
		response := core.MakeErrorResponse(401, "Updates Not Allowed for the Following Wallet Address", nil, nil, nil)
		c.JSON(http.StatusUnauthorized, response)
		return
	}

	if err := c.ShouldBindJSON(&data); err != nil {
		log.WithFields(log.Fields{
			"err": err,
		}).Error("failed to bind")

		response := core.MakeErrorResponse(400, err.Error(), nil, nil, nil)
		c.JSON(http.StatusUnprocessableEntity, response)
		return
	}

	client, err := core.RegisterClient(&data)
	if err != nil {
		log.WithFields(log.Fields{
			"err": err,
		}).Error("failed to create client")

		response := core.MakeErrorResponse(500, err.Error(), nil, nil, nil)
		c.JSON(http.StatusInternalServerError, response)
		return
	}

	response := core.MakeSucessResponse(201, "client created", nil, client, nil)

	c.JSON(http.StatusOK, response)

}

// swagger:route GET /client/{id} Client readClient
//
// # Read client
//
// Return client based on the given uuid.
// responses:
//
//	 200: clientSucessResponse
//	 400: badRequestResponse
//		401: unauthorizedResponse
//	 500: serverErrorResponse
func readClient(c *gin.Context) {
	id := c.Param("id")

	client, err := core.ReadClient(id)
	if err != nil {
		log.WithFields(log.Fields{
			"err": err,
		}).Error("failed to read client")

		response := core.MakeErrorResponse(500, err.Error(), nil, nil, nil)
		c.JSON(http.StatusInternalServerError, response)
		return
	}

	response := core.MakeSucessResponse(200, "client details", nil, client, nil)

	c.JSON(http.StatusOK, response)
}

// swagger:route PATCH /client/{id} Client updateClient
//
// # Update client
//
// Update client based on the given uuid and client model.
// responses:
//
//	 200: clientSucessResponse
//	 400: badRequestResponse
//		401: unauthorizedResponse
//	 500: serverErrorResponse
func updateClient(c *gin.Context) {
	var data model.Client
	id := c.Param("id")

	requestedWalletAddress, _ := c.Get("walletAddress")
	accept := middleware.CheckAuthorization(requestedWalletAddress)
	if !accept {
		log.WithFields(log.Fields{
			"err": "Updates Not Allowed for the Following Wallet Address",
		}).Error("Updates Not Allowed for the Following Wallet Address")
		response := core.MakeErrorResponse(401, "Updates Not Allowed for the Following Wallet Address", nil, nil, nil)
		c.JSON(http.StatusUnauthorized, response)
		return
	}

	if err := c.ShouldBindJSON(&data); err != nil {
		log.WithFields(log.Fields{
			"err": err,
		}).Error("failed to bind")

		response := core.MakeErrorResponse(400, err.Error(), nil, nil, nil)
		c.JSON(http.StatusUnprocessableEntity, response)
		return
	}

	client, err := core.UpdateClient(id, &data)
	if err != nil {
		log.WithFields(log.Fields{
			"err": err,
		}).Error("failed to update client")

		response := core.MakeErrorResponse(500, err.Error(), nil, nil, nil)
		c.JSON(http.StatusInternalServerError, response)
		return
	}

	response := core.MakeSucessResponse(200, "client updated", nil, client, nil)

	c.JSON(http.StatusOK, response)
}

// swagger:route DELETE /client/{id} Client deleteClient
//
// # Delete client
//
// Delete client based on the given uuid.
// responses:
//
//	 200: sucessResponse
//	 400: badRequestResponse
//		401: unauthorizedResponse
//	 500: serverErrorResponse
func deleteClient(c *gin.Context) {
	id := c.Param("id")

	requestedWalletAddress, _ := c.Get("walletAddress")
	accept := middleware.CheckAuthorization(requestedWalletAddress)
	if !accept {
		log.WithFields(log.Fields{
			"err": "Updates Not Allowed for the Following Wallet Address",
		}).Error("Updates Not Allowed for the Following Wallet Address")
		response := core.MakeErrorResponse(401, "Updates Not Allowed for the Following Wallet Address", nil, nil, nil)
		c.JSON(http.StatusUnauthorized, response)
		return
	}

	err := core.DeleteClient(id)
	if err != nil {
		log.WithFields(log.Fields{
			"err": err,
		}).Error("failed to remove client")

		response := core.MakeErrorResponse(500, err.Error(), nil, nil, nil)
		c.JSON(http.StatusInternalServerError, response)
		return
	}

	response := core.MakeSucessResponse(200, "client deleted", nil, nil, nil)

	c.JSON(http.StatusOK, response)
}

// swagger:route GET /client Client readClients
//
// # Read All Clients
//
// Get all clients in the server.
// responses:
//
//	 200: clientsSucessResponse
//	 400: badRequestResponse
//		401: unauthorizedResponse
//	 500: serverErrorResponse
func readClients(c *gin.Context) {
	clients, err := core.ReadClients()
	if err != nil {
		log.WithFields(log.Fields{
			"err": err,
		}).Error("failed to list clients")

		response := core.MakeErrorResponse(500, err.Error(), nil, nil, nil)
		c.JSON(http.StatusInternalServerError, response)
		return
	}

	response := core.MakeSucessResponse(200, "clients details", nil, nil, clients)

	c.JSON(http.StatusOK, response)
}

// swagger:route GET /client/{id}/config Client configClient
//
// # Get client configuration
//
// Return client configuration file in byte format based on the given uuid.
// produces:
//   - application/octet-stream
//   - application/json
//
// responses:
//
//	 200: configResponse
//	 400: badRequestResponse
//		401: unauthorizedResponse
//	 500: serverErrorResponse
func configClient(c *gin.Context) {
	configData, err := core.ReadClientConfig(c.Param("id"))
	if err != nil {
		log.WithFields(log.Fields{
			"err": err,
		}).Error("failed to read client config")
		c.AbortWithStatus(http.StatusInternalServerError)
		return
	}

	requestedWalletAddress, _ := c.Get("walletAddress")
	accept := middleware.CheckAuthorization(requestedWalletAddress)
	if !accept {
		log.WithFields(log.Fields{
			"err": "Updates Not Allowed for the Following Wallet Address",
		}).Error("Updates Not Allowed for the Following Wallet Address")
		response := core.MakeErrorResponse(401, "Updates Not Allowed for the Following Wallet Address", nil, nil, nil)
		c.JSON(http.StatusUnauthorized, response)
		return
	}

	formatQr := c.DefaultQuery("qrcode", "false")
	if formatQr == "false" {
		// return config as txt file
		c.Header("Content-Disposition", "attachment; filename="+c.Param("id")+".conf")
		c.Data(http.StatusOK, "application/config", configData)
		return
	}
	// return config as png qrcode
	png, err := qrcode.Encode(string(configData), qrcode.Medium, 250)
	if err != nil {
		log.WithFields(log.Fields{
			"err": err,
		}).Error("failed to create qrcode")
		c.AbortWithStatus(http.StatusInternalServerError)
		return
	}
	c.Data(http.StatusOK, "image/png", png)

}

// swagger:route GET /client/{id}/email Client emailClient
//
// # Email client Configuration
//
// Email the configuration file of the client to the email associated with client.
// responses:
//
//	 200: sucessResponse
//	 400: badRequestResponse
//		401: unauthorizedResponse
//	 500: serverErrorResponse
func emailClient(c *gin.Context) {
	id := c.Param("id")

	requestedWalletAddress, _ := c.Get("walletAddress")
	accept := middleware.CheckAuthorization(requestedWalletAddress)
	if !accept {
		log.WithFields(log.Fields{
			"err": "Updates Not Allowed for the Following Wallet Address",
		}).Error("Updates Not Allowed for the Following Wallet Address")
		response := core.MakeErrorResponse(401, "Updates Not Allowed for the Following Wallet Address", nil, nil, nil)
		c.JSON(http.StatusUnauthorized, response)
		return
	}

	err := core.EmailClient(id)
	if err != nil {
		log.WithFields(log.Fields{
			"err": err,
		}).Error("failed to send email to client")

		response := core.MakeErrorResponse(500, err.Error(), nil, nil, nil)
		c.JSON(http.StatusInternalServerError, response)
		return
	}

	response := core.MakeSucessResponse(200, "client configuration emailed", nil, nil, nil)

	c.JSON(http.StatusOK, response)
}
