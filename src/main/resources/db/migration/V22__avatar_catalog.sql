-- Fotos de perfil -> avatares ilustrados.
-- En una red de salud mental no se suben fotos: identifican a la persona,
-- pueden ser inapropiadas y hay que moderarlas. Ahora cada persona elige
-- uno de los avatares del catalogo (AvatarCatalog); null = iniciales.
-- Las fotos que ya existian se descartan (y se borran de Cloudinary: ver
-- docs/API_CONTRACT.md, "Avatares").
ALTER TABLE users ADD COLUMN avatar_id VARCHAR(32);
ALTER TABLE users DROP COLUMN avatar_url;
