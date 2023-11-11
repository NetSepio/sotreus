package ses

import (
	"github.com/aws/aws-sdk-go/aws"
	"github.com/aws/aws-sdk-go/aws/session"
	"github.com/aws/aws-sdk-go/service/ses"
)

func SendEmail() (*ses.SendEmailOutput, error) {
	sess, err := session.NewSession(&aws.Config{
		Region: aws.String("us-east-2"),
	})
	if err != nil {
		return nil, err
	}
	service := ses.New(sess)
	// Assemble the email.
	input := &ses.SendEmailInput{
		Destination: &ses.Destination{
			CcAddresses: []*string{},
			ToAddresses: []*string{
				aws.String(Recipient),
			},
		},
		Message: &ses.Message{
			Body: &ses.Body{
				Html: &ses.Content{
					Charset: aws.String(CharacterSet),
					Data:    aws.String(body),
				},
				/*Text: &ses.Content{
					Charset: aws.String(CharSet),
					Data:    aws.String(TextBody),
				},*/
			},
			Subject: &ses.Content{
				Charset: aws.String(CharacterSet),
				Data:    aws.String(subject),
			},
		},
		Source: aws.String(Sender),
	}

	// Attempt to send the email.
	res, err := svc.SendEmail(input)

	// Display error messages if they occur.
	if err != nil {
		return nil, err
	}

	return res, nil
}
