import { useNavigate } from "react-router-dom";
import { useContext, useEffect, useState } from "react";
import { AuthContext } from "../context/AuthContext";
import { useAccount, useSignMessage, useNetwork } from "wagmi";
import { getChallengeId, getToken } from "../modules/api";
import { useWallet } from "@aptos-labs/wallet-adapter-react";
import nacl from 'tweetnacl';

const Header = () => {
  const navigate = useNavigate();
  const authContext = useContext(AuthContext);
  // const useraddress = useAccount();
  const [message, setMessage] = useState<string>("");
  const [challengeId, setChallengeId] = useState<string>("");
  const [signature, setSignature] = useState<string | undefined>();
  const { signMessageAsync } = useSignMessage();
  const [profileId, setProfileId] = useState<string | null>(null);
  const [userWallet, setUserWallet] = useState<string | null>(null);

  const {
    connect,
    wallets,
    disconnect,
    wallet,
    account,
    network,
    connected,
    signMessage: petraSignMesssage,
    signMessageAndVerify,
  } = useWallet();

  const navigateDashboard = async () => {
    await navigate("/dashboard");
  };

  const navigateServer = async () => {
    await navigate("/server");
  };

  useEffect(() => {
    const storedToken = localStorage.getItem("token");
    if (storedToken) {
      authContext?.setIsSignedIn(true);
    }
    let timeoutId: string | number | NodeJS.Timeout | null = null;

    const getSignMessage = async () => {
      if (connected == false) {
        console.log("clearing localstorage")
        if (timeoutId !== null) {
          clearTimeout(timeoutId);
        }

        timeoutId = setTimeout(() => {
          signOut();
        }, 500);
      } else if (!authContext?.isSignedIn) {
        if (timeoutId !== null) {
          clearTimeout(timeoutId);
        }
      }
    };

    getSignMessage();

    return () => {
      if (timeoutId !== null) {
        clearTimeout(timeoutId);
      }
    };
  }, [authContext?.isSignedIn, account?.address, connected]);

  const getAptosWallet = () => {
    if ('aptos' in window) {
      return (window as any).aptos;
    } else {
      window.open('https://petra.app/', '_blank');
    }
  }
  const connectWallet = async () => {
    const wallet = getAptosWallet();
    try {
      const response = await wallet.connect();

      const account = await wallet.account();
      console.log("account",account)

      const challangeIdResponse = await getChallengeId(account?.address);
      if (challangeIdResponse.data.isAuthorized == true) {
        authContext?.setIsAuthorized(true);
      } else {
        authContext?.setIsAuthorized(false);
      }


      const message = challangeIdResponse.data.eula;
      const nonce = challangeIdResponse.data.challangeId;
      const publicKey = account.publicKey;

      const { signature, fullMessage } = await wallet.signMessage({
        message,
        nonce
      });
      console.log("sign", signature, "full message", fullMessage);

      const getTokenResponse = await getToken(`0x${signature}`, nonce, publicKey);
      if (getTokenResponse.data.token) {
        sessionStorage.setItem("token", getTokenResponse.data.token);
        localStorage.setItem("token", getTokenResponse.data.token);
        authContext?.setIsSignedIn(true);
      }

      setProfileId("");
      setUserWallet("");

    } catch (err) {
      console.log(err);
    }
  }

  const signOut = () => {
    sessionStorage.removeItem("token");
    localStorage.removeItem("token");
    localStorage.removeItem("AptosWalletName");
    setMessage("");
    setSignature("");
    setChallengeId("");
    authContext?.setIsSignedIn(false);
  };
  const connectPetra = async () => {
    connect(wallets[0].name);

  };

  const disconnectPetra = () => {
    disconnect();
  };

  const petraSign = async () => {
    const payload = {
      message: message,
      nonce: challengeId,
    };
    try {
      const signres = await petraSignMesssage(payload);
      console.log("response", signres);

      const response = await getToken(signres?.signature, challengeId, account?.publicKey);
      if (response.data.token) {
        sessionStorage.setItem("token", response.data.token);
        localStorage.setItem("token", response.data.token);
        authContext?.setIsSignedIn(true);
      }
  
      authContext?.setIsSignedIn(true);
    } catch (error: any) {
      console.log("error", error);
    }
  };

  return (
    <div>
      <div className="navbar">
        <div className="flex-1">
          <a
            href="/"
            className="btn btn-ghost normal-case text-transparent bg-clip-text bg-gradient-to-r from-blue-400 to-blue-200 text-2xl md:text-3xl"
          >
            Sotreus
          </a>
        </div>
        <div className="flex-none">
          <ul className="menu menu-horizontal px-1">
            <div className="flex space-x-2">
              <li className="relative inline-block text-left">
                  <div className="absolute left-0 z-10 mt-2 origin-top-right rounded-md bg-none shadow-lg ring-1 ring-black ring-opacity-5 focus:outline-none">
                    <div className="-m-4" role="none">

                    </div>
                  </div>
              </li>
              {(!account?.address || authContext?.isSignedIn) && (
                <li>
                  <button
                    onClick={() => connectWallet()}
                    className="border text-blue-200 border-blue hover:bg-blue-300 hover:border-black hover:text-black font-bold transition focus:ring focus:ring-blue-500 focus:ring-opacity-80"
                  >
                    <img src={`${wallets[0].icon}`} width={20}></img> Connect{" "}
                    {wallets[0].name}
                  </button>
                </li>
              )}
              {/* {!(connected && authContext?.isSignedIn) && (
                  <li>
                    <button
                      onClick={petraSign}
                      className="border text-blue-200 border-blue hover:bg-blue-300 hover:border-black hover:text-black font-bold transition focus:ring focus:ring-blue-500 focus:ring-opacity-80"
                    >
                      Sign In
                    </button>
                  </li>
                )} */}
               {(authContext?.isSignedIn) && (
                  <li>
                    <div

                      className="border text-blue-200 border-blue hover:bg-blue-300 hover:border-black hover:text-black font-bold transition focus:ring focus:ring-blue-500 focus:ring-opacity-80"
                    >
                      Details
                    </div>
                  </li>
                )}
              <li>
                <button
                  onClick={navigateDashboard}
                  className="border text-blue-200 border-blue hover:bg-blue-300 hover:border-black hover:text-black font-bold transition focus:ring focus:ring-blue-500 focus:ring-opacity-80"
                >
                  Dashboard
                </button>
              </li>
            </div>
          </ul>
        </div>
      </div>
    </div>
  );
};

export default Header;
