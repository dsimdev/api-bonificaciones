-- Quien genero cada api-key. Mismo motivo que creada_por en distribuidora: cuando una clave
-- aparece donde no debia, hay que saber quien la genero y cuando.
ALTER TABLE credencial ADD creada_por VARCHAR(100) NULL;
GO
