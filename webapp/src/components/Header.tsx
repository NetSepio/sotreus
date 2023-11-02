import { useNavigate } from "react-router-dom";
import { useContext, useEffect, useState } from "react";
import { AuthContext } from "../context/AuthContext";
import { useAccount, useSignMessage, useNetwork } from "wagmi";
import { getChallengeId, getToken } from "../modules/api";
import { ConnectButton } from "@rainbow-me/rainbowkit";
import { useWallet } from "@aptos-labs/wallet-adapter-react";

const Header = () => {
  const navigate = useNavigate();
  const authContext = useContext(AuthContext);
  const useraddress = useAccount();
  const [Pubkey, setPubkey] = useState<string>("");
  const [message, setMessage] = useState<string>("");
  const [challengeId, setChallengeId] = useState<string>("");
  const [signature, setSignature] = useState<string | undefined>();
  const { signMessageAsync } = useSignMessage();
  const { isConnected, address } = useAccount();

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
      if (address == undefined) {
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

        const response = await getChallengeId(address);
        setMessage(response.data.eula + response.data.challangeId);
        setChallengeId(response.data.challangeId);
        if (response.data.isAuthorized == true) {
          authContext?.setIsAuthorized(true);
        } else {
          authContext?.setIsAuthorized(false);
        }
      }
    };

    getSignMessage();

    return () => {
      if (timeoutId !== null) {
        clearTimeout(timeoutId);
      }
    };
  }, [authContext?.isSignedIn, address]);

  // const signMessageFunc = async () => {
  //   const signature = await signMessageAsync({ message });
  //   setSignature(signature);
  //   //make a post request to the sotreus server with the signature and challengeId

  //   const response = await getToken(signature, challengeId);
  //   if (response.data.token) {
  //     //store the token in the session storage
  //     sessionStorage.setItem("token", response.data.token);
  //     localStorage.setItem("token", response.data.token);
  //     authContext?.setIsSignedIn(true);
  //   }
  // };
  const signOut = () => {
    sessionStorage.removeItem("token");
    localStorage.removeItem("token");
    setMessage("");
    setSignature("");
    setChallengeId("");
    authContext?.setIsSignedIn(false);
  };
  const connectPetra = async () => {
    // wallets[0]
    //   .connect()
    //   .then((res) => {
    //     console.log(wallets[0]);
    //   })
    //   .catch((err) => {
    //     console.log(err);
    //   });
    connect(wallets[0].name);
  };
  const disconnectPetra = () => {
    disconnect();
  };

  const petraSign = async () => {
    const payload = {
      message: "Hello from Aptos Wallet Adapter",
      nonce: "random_string",
    };
    try {
      const res = await petraSignMesssage(payload);
      console.log("response", res);
          const response = await getToken(signature, challengeId, Pubkey);
    if (response.data.token) {
      //store the token in the session storage
      sessionStorage.setItem("token", response.data.token);
      localStorage.setItem("token", response.data.token);
      authContext?.setIsSignedIn(true);
    }

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
              {(!address || authContext?.isSignedIn) && (
                <li>
                  <button
                    onClick={() => connectPetra()}
                    className="border text-blue-200 border-blue hover:bg-blue-300 hover:border-black hover:text-black font-bold transition focus:ring focus:ring-blue-500 focus:ring-opacity-80"
                  >
                    <img src="{wallets[0].icon}"></img> Connect{" "}
                    {wallets[0].name}
                  </button>
                </li>
              )}
              {!(isConnected && authContext?.isSignedIn) && (
                  <li>
                    <button
                      onClick={petraSign}
                      className="border text-blue-200 border-blue hover:bg-blue-300 hover:border-black hover:text-black font-bold transition focus:ring focus:ring-blue-500 focus:ring-opacity-80"
                    >
                      Sign In
                    </button>
                  </li>
                )}
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
