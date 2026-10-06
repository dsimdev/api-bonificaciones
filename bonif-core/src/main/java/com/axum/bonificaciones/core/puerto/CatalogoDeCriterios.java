package com.axum.bonificaciones.core.puerto;

import com.axum.bonificaciones.core.model.Criterio;
import java.util.List;

/**
 * Puerto de lectura de criterios de venta. Lo implementa un conector por ERP.
 *
 * El core no sabe de HTTP, tokens ni JSON: eso vive en el conector, del lado de bonif-app.
 */
public interface CatalogoDeCriterios {

    List<Criterio> criterios(String distribuidora);
}
