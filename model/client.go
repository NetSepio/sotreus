package model

import (
	"fmt"

	"github.com/NetSepio/sotreus/util"
)

// Client structure
/*type Client struct {
	UUID                      string    `json:"uuid"`
	Name                      string    `json:"name"`
	Tags                      []string  `json:"tags"`
	Email                     string    `json:"email"`
	Enable                    bool      `json:"enable"`
	IgnorePersistentKeepalive bool      `json:"ignorePersistentKeepalive"`
	PresharedKey              string    `json:"presharedKey"`
	AllowedIPs                []string  `json:"allowedIPs"`
	Address                   []string  `json:"address"`
	PrivateKey                string    `json:"privateKey"`
	PublicKey                 string    `json:"publicKey"`
	CreatedBy                 string    `json:"createdBy"`
	UpdatedBy                 string    `json:"updatedBy"`
	Created                   int64 `json:"created"`
	Updated                   int64 `json:"updated"`
}*/

// IsValid check if model is valid
func (a Client) IsValid() []error {
	errs := make([]error, 0)

	// check if the name is empty
	if a.Name == "" {
		errs = append(errs, fmt.Errorf("name is required"))
	}
	// check the name field is between 3 to 40 chars
	if len(a.Name) < 2 || len(a.Name) > 40 {
		errs = append(errs, fmt.Errorf("name field must be between 2-40 chars"))
	}
	// email is not required, but if provided must match regex
	if a.Email != "" {
		if !util.RegexpEmail.MatchString(a.Email) {
			errs = append(errs, fmt.Errorf("email %s is invalid", a.Email))
		}
	}
	// check if the allowedIPs empty
	if len(a.AllowedIPs) == 0 {
		errs = append(errs, fmt.Errorf("allowedIPs field is required"))
	}
	// check if the allowedIPs are valid
	for _, allowedIP := range a.AllowedIPs {
		if !util.IsValidCidr(allowedIP) {
			errs = append(errs, fmt.Errorf("allowedIP %s is invalid", allowedIP))
		}
	}
	// check if the address empty
	if len(a.Address) == 0 {
		errs = append(errs, fmt.Errorf("address field is required"))
	}
	// check if the address are valid
	for _, address := range a.Address {
		if !util.IsValidCidr(address) {
			errs = append(errs, fmt.Errorf("address %s is invalid", address))
		}
	}

	return errs
}
