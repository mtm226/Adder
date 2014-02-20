
import java.net.*;
import java.io.*;
import java.util.*;

public class Client {

	public static final int PORT = 3310;
	public static YhteysKasittelija yhteysKasittelija;
	private static ServerSocket palvelin_soketti;

	@SuppressWarnings("resource")
	public static void main(String[] args) {

		/* Luodaan soketti porttiin 3310 */
		try {
			palvelin_soketti = new ServerSocket(PORT);
		} catch (Exception e1) {
			System.out.println("Soketin luonti virhe: " + e1.getMessage());
		}

		/* Yritet‰‰n yhteydenmuodostusta 5 kertaa. */
		for (int i = 0; i < 5; i++) {
			UDPConnect();

			/* Odotetaan 1 sekunti ennen TCP-yhteyden avaamista. */
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e1) {
				e1.printStackTrace();
			}

			System.out.println("Yritet‰‰n TCP yhteytt‰...");
			Socket soketti;
			try {
				// Asetetaan 4 sekunnin timeOut TCP yhteydenotolle.
				palvelin_soketti.setSoTimeout(4000);
				// Soketti, joka vastaa WorkDistributor luokan sokettia
				soketti = new Socket();
				// Aletaan kuunnella. Blockaa kunnes timeout tai yhteys asiakkaaseen saadaan muodostettua.
				soketti = palvelin_soketti.accept();
			} catch (SocketTimeoutException e) {
				System.out.println("Ei yhteytt‰ odotusaikana");
				continue;
			} catch (IOException e) {
				System.out.println("Listen socketin avaaminen ep‰onnistui");
				e.printStackTrace();
				break;
			}

			// Ilmaistaan yhteyden l‰hde
			System.out.println("Yhteys portista " + soketti.getInetAddress() + "/"
					+ soketti.getPort());

			if (soketti.getInetAddress() != null) {
				// K‰ynnistet‰‰n yhteysk‰sittelij‰ s‰ie, kun TCP yhteys on avattu.
				yhteysKasittelija = new YhteysKasittelija(soketti);
				yhteysKasittelija.OpenConnections();
			}

			try {
				System.out.println("Suljetaan sovellus.");
				soketti.close();
				palvelin_soketti.close();
				System.exit(0);
			} catch (IOException e) {
				System.out.println("Sovelluksen sulkeminen ep‰onnistui");
				e.printStackTrace();
			}
		}
	}

	// Muodostetaan UDP yhteys ja l‰hetet‰‰n paketti palvelimelle
	private static void UDPConnect() {
		System.out.println("Yritet‰‰n UDP yhteytt‰...");
		try {
			String message = "" + PORT + "";
			DatagramSocket dataSocket = new DatagramSocket();
			byte[] sendData = new byte[256];
			InetAddress receiverAddress = InetAddress.getLocalHost();
			sendData = message.getBytes();
			DatagramPacket sentPacket = new DatagramPacket(sendData,
					sendData.length, receiverAddress, 3126);
			dataSocket.send(sentPacket);
			dataSocket.close();
		} catch (Exception e) {
			System.out.println("UDP yhteysvirhe: " + e.getMessage());
		}
	}
}

/* T‰m‰ luokka vastaa TCP yhteden k‰sittelyst‰ ja summauspalvelijoiden k‰ynnist‰misest‰ */
class YhteysKasittelija {
	private Socket asiakas;
	private int kysymys;
	private Thread[] summaajat;
	private int sumPortCount;

	public final static int firstTCPPort = 3200;

	/* Taulukko, joka sis‰lt‰‰ vapaita portteja max 10 kpl */
	public int[] portit;

	/* Getteri summaajaporttien m‰‰r‰lle */
	public int getSummaajaPortit() {
		return this.sumPortCount;
	}

	public YhteysKasittelija(Socket s) {
		asiakas = s;
	}

	public void OpenConnections() {
		try {
			System.out.println("TCP PƒƒLLƒ");
			asiakas.setSoTimeout(5000);

			ObjectOutputStream olioMenoVirta = new ObjectOutputStream(asiakas.getOutputStream());
			ObjectInputStream olioTuloVirta = new ObjectInputStream(asiakas.getInputStream());

			try {
				while (true) {
					// Vastaanotetaan palvelimen random luku, joka m‰‰ritt‰‰ summaajien m‰‰r‰n
					sumPortCount = olioTuloVirta.read();

					if (sumPortCount == 0) {
						continue;
					} else if (sumPortCount >= 2 && sumPortCount <= 10) {
						System.out.println("Haluttu summaajien m‰‰r‰ on " + sumPortCount);
						portit = new int[sumPortCount];
						for (int i = 0; i < sumPortCount; i++)
							portit[i] = firstTCPPort + i;

						break;
					} else {
						System.out.println("Summaajien lukum‰‰r‰‰ ei saatu. Ei v‰lill‰ 2...10. Saatiin " + sumPortCount);
						olioMenoVirta.writeInt(-1);
						olioMenoVirta.flush();
						System.out.println("Suljetaan sovellus.");
						System.exit(0);
					}
				}

				// L‰hetet‰‰n porttinumerot WorkDistributorille.
				for (int j = 0; j < sumPortCount; j++) {
					try {
						olioMenoVirta.writeInt(portit[j]);
						olioMenoVirta.flush();
					} catch (Exception e) {
						System.out.println("Porttien l‰hetyksess‰ virhe: " + e.getMessage());
					}
				}

				//Luodaan tietovarasto lukujen tallentamista varten
				TietoVarasto varasto = new TietoVarasto(sumPortCount);
				Thread.sleep(100);

				// K‰ynnistet‰‰n summauspalvelijat oikeissa porteissa
				summaajat = new Thread[sumPortCount];
				for (int i = 0; i < summaajat.length; i++) {
					summaajat[i] = new SummausPalvelija(portit[i], varasto, i);
					summaajat[i].start();
				}

				try {
					// Jos uteluissa minuutin tauko, suljetaan soketti
					asiakas.setSoTimeout(60000);
					while (true) {
						// Luetaan kysymyksen numero palvelimelta
						try {
							kysymys = olioTuloVirta.readInt();
							System.out.println("Palvelimen kysymys = " + kysymys);
							if (kysymys == 1) {
								// Vastataan v‰litettyjen lukujen kokonaissummalla
								System.out.println("TIETOVARASTOSTA: " + varasto.getKokonaisSumma());
								olioMenoVirta.writeInt(varasto.getKokonaisSumma());
								olioMenoVirta.flush();
							} else if (kysymys == 2) {
								// Vastataan summaajapalvelimien lukujen suurimmalla summalla
								System.out.println("TIETOVARASTOSTA: " + varasto.getSuurinLukumaara());
								olioMenoVirta.writeInt(varasto.getSuurinLukumaara());
								olioMenoVirta.flush();
							} else if (kysymys == 3) {
								// Vastataan v‰litettyjen lukujen kokonaism‰‰r‰ll‰
								System.out.println("TIETOVARASTOSTA: " + varasto.getKaikkienLukumaara());
								olioMenoVirta.writeInt(varasto.getKaikkienLukumaara()); 
								olioMenoVirta.flush();
							} else if (kysymys == 0) {
								System.out.println("Suljetaan summaajat...");
								for (int k = 0; k < sumPortCount; k++) {
									// Kun saadaan nolla lopetetaan k‰ynniss‰ olevat s‰ikeet sek‰ soketti
									summaajat[k].interrupt();
									olioMenoVirta.close();
									olioTuloVirta.close();
									asiakas.close();
								}
								break;
							}
						} catch (EOFException e) {
							continue;
						}
					}
				} catch (Exception timeOut) {
					System.out.println("Ei uteluita kuluneen minuutin aikana. Suljetaan sovellus.");
				}
			} catch (Exception e) {
				System.out.println("Yhteysk‰sittelij‰n virhe: " + e.toString());
				asiakas.close();
			}
		} catch (Exception e) {
			System.out.println("Yhteysk‰sittelij‰n virhe: " + e.getMessage());
		}
	}
}


class SummausPalvelija extends Thread {
	private int portti;
	private int luku;
	private int tunnus;
	private TietoVarasto varasto;

	public SummausPalvelija(int portti, TietoVarasto varasto, int i) throws Exception {
		this.portti = portti;
		this.tunnus = i;
		this.varasto = varasto;
	}

	public synchronized void tallenna(int tallennettava, int tunnus){
		varasto.tallennaLuku(tallennettava, tunnus);
	}

	public void run() {
		try {
			// Liit‰nt‰ summauspalvelijan ja palvelimen v‰lill‰
			ServerSocket ss = new ServerSocket(portti);
			Socket cs = ss.accept();
			InputStream tuloVirta = cs.getInputStream();
			OutputStream menoVirta = cs.getOutputStream();
			ObjectOutputStream olioVastaaja = new ObjectOutputStream(menoVirta);
			ObjectInputStream olioKysyj‰ = new ObjectInputStream(tuloVirta);
			System.out.println("S‰ie k‰ynniss‰ portista " + cs.getPort() + " porttiin " + portti);

			while (true) {
				try {
					luku = olioKysyj‰.readInt();
					tallenna(luku, tunnus);

				} catch (Exception e) {
					System.out.println(e.getMessage());
				}
				Thread.sleep(100);
				System.out.println("Portin " + portti + " luku palvelimelta on " + luku);

				if (luku == 0) {
					break;
				}
			}

			// Kun saadaa nolla, suljetaan kaikki
			tuloVirta.close();
			menoVirta.close();
			olioKysyj‰.close();
			olioVastaaja.close();
			ss.close();
			cs.close();

		} catch (Exception e) {
			System.out.println("Summaajapalvelimen virhe: " + e.toString());
		}
	}
}


/* Luokka joka varastoi Summauspalvelijoiden vastaanottamat tiedot */
class TietoVarasto {

	private int kokonaisLukumaara;

	/* Lukujonot tallennetaan listaan */
	public ArrayList<ArrayList<Integer>> tulokset;

	/* Konstruktori */
	public TietoVarasto(int summaajat) throws Exception {
		tulokset = new ArrayList<ArrayList<Integer>>(summaajat);
		for (int i = 0; i < tulokset.size(); i++) {
			System.out.println("Luodaan Tietovarasto!");
			tulokset.add(new ArrayList<Integer>());
		}
	}

	public void tallennaLuku(int numero, int tunnus) {
		//System.out.println("TIETOVARASTON TALLENNUS: " + tulokset.get(tunnus).get(kokonaisLukumaara));
		tulokset.get(tunnus).add(numero);

		//Aina, kun summaaja k‰ytt‰‰ t‰t‰ metodia, kokonaislukum‰‰r‰‰ luvuista kasvatetaan
		kokonaisLukumaara++;
	}

	/* Metodi v‰litettyjen lukujen kokonaissummaa varten */
	public int getKokonaisSumma() {
		int summa = 0;
		for (int i = 0; i < tulokset.size(); i++) {
			for (int j = 0; j < tulokset.get(i).size(); j++) {
				summa += tulokset.get(i).get(j);
			}
		}
		return summa;
	}

	/* Mill‰ portilla on suurin lukum‰‰r‰ lukuja */
	public int getSuurinLukumaara() {
		int suurinLukumaara = 0;
		int indexi = 0;
		for (int i = 0; i < tulokset.size(); i++) {
			if (tulokset.get(i).size() > suurinLukumaara) {
				suurinLukumaara = tulokset.get(i).size();
			}
		}
		return suurinLukumaara;
	}

	/* Palauttaa kaikille s‰ikeille annettujen lukujen kokonaism‰‰r‰n */
	public int getKaikkienLukumaara() {
		return kokonaisLukumaara;
	}
}