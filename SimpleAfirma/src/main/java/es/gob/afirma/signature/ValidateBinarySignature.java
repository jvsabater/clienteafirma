package es.gob.afirma.signature;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Security;
import java.security.cert.CRLException;
import java.security.cert.CertStore;
import java.security.cert.CertStoreException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.util.Iterator;

import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.SignerInformationStore;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import sun.security.x509.AlgorithmId;
import es.gob.afirma.signature.SignValidity.SIGN_DETAIL_TYPE;
import es.gob.afirma.signature.SignValidity.VALIDITY_ERROR;
import es.gob.afirma.signers.AOCAdESSigner;
import es.gob.afirma.signers.AOCMSSigner;
import es.gob.afirma.signers.AOSigner;

/**
 * Validador de firmas Adobe PDF.
 * La validaci&oacute;n de firmas est&aacute; basada en el c&oacute;digo de pruebas
 * proporcionado con las bibliotecas de BouncyCastle.
 * @author Carlos Gamuci
 */
public final class ValidateBinarySignature {

    /**
     * Valida una firma binaria (CMS/CAdES). Si se especifican los datos que se firmaron
     * se comprobar&aacute; que efectivamente fueron estos, mientras que si no se indican
     * se extraer&aacute;n de la propia firma. Si la firma no contiene los datos no se realizara
     * esta comprobaci&oacute;n.
     * @param sign Firma binaria
     * @param data Datos firmados o {@code null} si se desea comprobar contra los datos incrustados
     * en la firma.
     * @return <code>true</code> si la firma es v&aacute;lida, <code>false</code> en caso contrario
     */
    public static SignValidity validate(final byte[] sign, final byte[] data) {
    	if (sign == null) {
    		throw new NullPointerException("La firma a validar no puede ser nula"); //$NON-NLS-1$
    	}

    	AOSigner signer = new AOCMSSigner();
    	if (!signer.isSign(sign)) {
    	    signer = new AOCAdESSigner();
    	    if (!signer.isSign(sign)) {
    	        return new SignValidity(SIGN_DETAIL_TYPE.KO, null);
    	    }
    	}

    	Security.addProvider(new BouncyCastleProvider());

    	try {
    	    byte[] signedData = null;
    	    if (data == null) {
    	        signedData = signer.getData(sign);
    	        if (signedData == null) {
    	            return new SignValidity(SIGN_DETAIL_TYPE.UNKNOWN, VALIDITY_ERROR.NO_DATA);
    	        }
    	    }
    	    verifySignatures(sign, data != null ? data : signedData);
    	} catch (final CertStoreException e) {
    	    // Ocurrio un error al recuperar los certificados o estos no son validos
            return new SignValidity(SIGN_DETAIL_TYPE.KO, VALIDITY_ERROR.CERTIFICATE_PROBLEM);
        } catch (final CertificateExpiredException e) {
         // Certificado caducado
            return new SignValidity(SIGN_DETAIL_TYPE.KO, VALIDITY_ERROR.CERTIFICATE_EXPIRED);
        } catch (final CertificateNotYetValidException e) {
         // Certificado aun no valido
            return new SignValidity(SIGN_DETAIL_TYPE.KO, VALIDITY_ERROR.CERTIFICATE_NOT_VALID_YET);
        } catch (final NoSuchAlgorithmException e) {
         // Algoritmo no reconocido
            return new SignValidity(SIGN_DETAIL_TYPE.KO, VALIDITY_ERROR.ALGORITHM_NOT_SUPPORTED);
        } catch (final NoMatchDataException e) {
         // Los datos indicados no coinciden con los datos de firma
            return new SignValidity(SIGN_DETAIL_TYPE.KO, VALIDITY_ERROR.NO_MATCH_DATA);
        } catch (final CRLException e) {
         // Problema en la validacion de las CRLs de la firma
            return new SignValidity(SIGN_DETAIL_TYPE.KO, VALIDITY_ERROR.CRL_PROBLEM);
        } catch (final Exception e) {
            // La firma no es una firma binaria valida
            return new SignValidity(SIGN_DETAIL_TYPE.KO, null);
        }

    	return new SignValidity(SIGN_DETAIL_TYPE.OK, null);
    }

    /**
     * Verifica la valides de una firma. Si la firma es v&aacute;lida, no hace nada. Si no es
     * v&aacute;lida, lanza una excepci&oacute;n.
     * @param sign Firma que se desea validar.
     * @param data Datos para la comprobaci&oacute;n.
     * @throws CMSException Cuando la firma no tenga una estructura v&aacute;lida.
     * @throws CertStoreException Cuando se encuentra un error en los certificados de
     * firma o estos no pueden recuperarse.
     * @throws CertificateExpiredException Cuando el certificado est&aacute;a caducado.
     * @throws CertificateNotYetValidException Cuando el certificado aun no es v&aacute;lido.
     * @throws NoSuchAlgorithmException Cuando no se reconoce o soporta alguno de los
     * algoritmos utilizados en la firma.
     * @throws NoMatchDataException Cuando los datos introducidos no coinciden con los firmados.
     * @throws CRLException Cuando ocurre un error con las CRL de la firma.
     * @throws Exception Cuando la firma resulte no v&aacute;lida.
     */
    private static void verifySignatures(final byte[] sign, final byte[] data) throws CMSException, CertStoreException, CertificateExpiredException, CertificateNotYetValidException, NoSuchAlgorithmException, NoMatchDataException, CRLException, Exception {

        final String BC = BouncyCastleProvider.PROVIDER_NAME;

        final CMSSignedData s = new CMSSignedData(sign);
        final CertStore certStore = s.getCertificatesAndCRLs("Collection", BC);  //$NON-NLS-1$
        final SignerInformationStore signers = s.getSignerInfos();
        final Iterator<?> it = signers.getSigners().iterator();

        while (it.hasNext()) {
            final SignerInformation signer = (SignerInformation) it.next();
            final Iterator<?> certIt = certStore.getCertificates(signer.getSID()).iterator();
            final X509Certificate cert = (X509Certificate) certIt.next();

            if (!signer.verify(cert, BC)) {
                throw new Exception("Firma no valida"); //$NON-NLS-1$
            }

            if (data != null) {
                if (signer.getDigestAlgorithmID() == null) {
                    throw new CMSException("No se ha podido localizar el algoritmo de huella digital"); //$NON-NLS-1$
                }

                String mdAlgorithm;
                final String mdAlgorithmOID = signer.getDigestAlgorithmID().getAlgorithm().toString();
                if (AlgorithmId.MD2_oid.toString().equals(mdAlgorithmOID)) {
                    mdAlgorithm = "MD2"; //$NON-NLS-1$
                }
                else if (AlgorithmId.MD5_oid.toString().equals(mdAlgorithmOID)) {
                    mdAlgorithm = "MD5"; //$NON-NLS-1$
                }
                else if (AlgorithmId.SHA_oid.toString().equals(mdAlgorithmOID)) {
                    mdAlgorithm = "SHA1"; //$NON-NLS-1$
                }
                else if (AlgorithmId.SHA256_oid.toString().equals(mdAlgorithmOID)) {
                    mdAlgorithm = "SHA256"; //$NON-NLS-1$
                }
                else if (AlgorithmId.SHA384_oid.toString().equals(mdAlgorithmOID)) {
                    mdAlgorithm = "SHA384"; //$NON-NLS-1$
                }
                else if (AlgorithmId.SHA512_oid.toString().equals(mdAlgorithmOID)) {
                    mdAlgorithm = "SHA512"; //$NON-NLS-1$
                } else {
                    throw new NoSuchAlgorithmException("Algoritmo de huella digital no reconocido"); //$NON-NLS-1$
                }

                if (!MessageDigest.isEqual(MessageDigest.getInstance(mdAlgorithm).digest(data),
                        signer.getContentDigest())) {
                    throw new NoMatchDataException("Los datos introducidos no coinciden con los firmados"); //$NON-NLS-1$
                }
            }
        }

        if (certStore.getCertificates(null).size() != s.getCertificates("Collection", BC).getMatches(null).size()) { //$NON-NLS-1$
            throw new CertStoreException("Error en la estructura de certificados de la firma");  //$NON-NLS-1$
        }
        if (certStore.getCRLs(null).size() != s.getCRLs("Collection", BC).getMatches(null).size()) { //$NON-NLS-1$
            throw new CRLException("Error en la estructura de CRLs de la firma"); //$NON-NLS-1$
        }
    }

//  public static void main(String[] args) throws Exception {
//  ValidateBinarySignature.verifySignatures(
//          AOUtil.getDataFromInputStream(AOUtil.loadFile(
//                  AOUtil.createURI("C:\\Users\\A122466\\Desktop\\borrar\\evisor.properties.csig2.csig"), null, false)),
//          null);
//
//  System.out.println("OK");
//}
}
