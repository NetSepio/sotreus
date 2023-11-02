package server

import (
	"net/http"
	"os"

	"github.com/NetSepio/sotreus/api/v1/authenticate/paseto"
	"github.com/NetSepio/sotreus/api/v1/middleware"
	"github.com/NetSepio/sotreus/core"
	"github.com/NetSepio/sotreus/model"
	"github.com/NetSepio/sotreus/util"

	"github.com/gin-gonic/gin"
	log "github.com/sirupsen/logrus"
)

// ApplyRoutes applies router to gin Router
func ApplyRoutes(r *gin.RouterGroup) {
	g := r.Group("/server")
	{
		g.Use(paseto.PASETO)
		g.GET("", readServer)
		g.PATCH("", updateServer)
		g.GET("/config", configServer)
	}
}

// swagger:route GET /server Server readServer
//
// # Read Server
//
// Retrieves the server details.
// responses:
//
//	 200: serverSucessResponse
//	 400: badRequestResponse
//		401: unauthorizedResponse
//	 500: serverErrorResponse
func readServer(c *gin.Context) {
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
	server, err := core.ReadServer()
	if err != nil {
		log.WithFields(util.StandardFields).Error("Failure in reading server")
		response := core.MakeErrorResponse(500, err.Error(), nil, nil, nil)
		c.JSON(http.StatusInternalServerError, response)
		return
	}

	response := core.MakeSucessResponse(200, "server details", server, nil, nil)

	c.JSON(http.StatusOK, response)
}

// swagger:route PATCH /server Server updateServer
//
// # Update Server
//
// Update the server with given details.
// responses:
//
//	 200: serverSucessResponse
//	 400: badRequestResponse
//		401: unauthorizedResponse
//	 500: serverErrorResponse
func updateServer(c *gin.Context) {
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

	var data model.Server

	if err := c.ShouldBindJSON(&data); err != nil {
		log.WithFields(util.StandardFields).Error("failed to bind")
		response := core.MakeErrorResponse(500, err.Error(), nil, nil, nil)
		c.JSON(http.StatusInternalServerError, response)
		return
	}

	server, err := core.UpdateServer(&data)
	if err != nil {
		log.WithFields(util.StandardFields).Error("failed to update server")
		response := core.MakeErrorResponse(500, err.Error(), nil, nil, nil)
		c.JSON(http.StatusInternalServerError, response)
		return
	}

	response := core.MakeSucessResponse(200, "server updated", server, nil, nil)

	c.JSON(http.StatusOK, response)
}

// swagger:route GET /server/config Server configServer
//
// Get Server Configuration
// Retrieves the server configuration details.
// responses:
//
//	 200: configResponse
//	 400: badRequestResponse
//		401: unauthorizedResponse
//	 500: serverErrorResponse
func configServer(c *gin.Context) {
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

	configData, err := core.ReadWgConfigFile()
	if err != nil {
		log.WithFields(util.StandardFields).Error("Failed to read wireguard config file")
		c.AbortWithStatus(http.StatusInternalServerError)
		return
	}

	// return config as txt file
	c.Header("Content-Disposition", "attachment; filename="+os.Getenv("WG_INTERFACE_NAME")+"")
	c.Data(http.StatusOK, "application/config", configData)
}
